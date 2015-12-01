/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.eureka2.transport.base;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.netflix.eureka2.spi.protocol.ProtocolModel;
import com.netflix.eureka2.spi.model.Heartbeat;
import com.netflix.eureka2.spi.transport.EurekaConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Scheduler;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Func1;
import rx.subjects.PublishSubject;

import static com.netflix.eureka2.utils.ExceptionUtils.trimStackTraceof;

/**
 * A decorator for {@link EurekaConnection} which sends heartbeat messages, to monitor
 * connection health.
 *
 * For every heartbeat received, this counter is decremented by 1 and for every heartbeat check tick it is
 * incremented by 1.
 * At steady state, this should be -1, 0 or 1 depending on which thread is faster i.e. heartbeat or heartbeat
 * checker.
 * When the heartbeats are missed, the counter increases and when it becomes >= max tolerable mixed heartbeat, the
 * connection is closed.
 *
 * <h1>Concurrency</h1>
 * Heartbeats are sent concurrently with the primary protocol messages. This is fine, as semantically they are
 * independent and can be interleaved with other messages. The heartbeat message is created by an external thread
 * (from Netty's perspective), and is put on the a queue, which enforces serialized write down the channel.
 * Since we write objects to the channel, not their serialized form, there is no risk of interleaving of
 * chunks of object data (for example few bytes of protocol message, followed by heartbeat message binary data).
 *
 * @author Tomasz Bak
 */
public class HeartBeatConnection implements EurekaConnection {

    private static final Logger logger = LoggerFactory.getLogger(HeartBeatConnection.class);

    protected static final IllegalStateException MISSING_HEARTBEAT_EXCEPTION = trimStackTraceof(new IllegalStateException("too many heartbeats missed"));

    private final EurekaConnection delegate;
    private final long heartbeatIntervalMs;
    private final long tolerance;
    private final Scheduler scheduler;

    private final HeartbeatSenderReceiver heartbeatSenderReceiver;
    private final PublishSubject<Object> filteredInput;

    private final Subscription ackInputSubscription;

    public HeartBeatConnection(EurekaConnection delegate, long heartbeatIntervalMs, long tolerance, Scheduler scheduler) {
        this.delegate = delegate;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.tolerance = tolerance;
        this.scheduler = scheduler;

        this.filteredInput = PublishSubject.create();
        this.heartbeatSenderReceiver = new HeartbeatSenderReceiver();

        ackInputSubscription = delegate.incoming()
                .filter(new Func1<Object, Boolean>() {
                    @Override
                    public Boolean call(Object o) {
                        if (o instanceof Heartbeat) {
                            heartbeatSenderReceiver.onHeartbeatReceived();
                            return false;
                        }
                        return true;
                    }
                })
                .subscribe(filteredInput);

        delegate.lifecycleObservable().subscribe(new Subscriber<Void>() {
            @Override
            public void onCompleted() {
                internalShutdown();
            }

            @Override
            public void onError(Throwable e) {
                internalShutdown();
            }

            @Override
            public void onNext(Void aVoid) {
                // No-op
            }
        });
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public Observable<Void> submit(Object message) {
        return delegate.submit(message);
    }

    @Override
    public Observable<Void> submitWithAck(Object message) {
        return delegate.submitWithAck(message);
    }

    @Override
    public Observable<Void> submitWithAck(Object message, long timeout) {
        return delegate.submitWithAck(message, timeout);
    }

    @Override
    public Observable<Void> acknowledge() {
        return delegate.acknowledge();
    }

    // TODO: We need buffering observable that will fail if client does not consume messages after configurable threshold is reached.
    @Override
    public Observable<Object> incoming() {
        return filteredInput;
    }

    @Override
    public Observable<Void> onError(Throwable error) {
        return Observable.error(error);
    }

    @Override
    public Observable<Void> onCompleted() {
        return Observable.empty();
    }

    @Override
    public void shutdown() {
        heartbeatSenderReceiver.unsubscribe();
        delegate.shutdown();
    }

    @Override
    public void shutdown(Throwable e) {
        internalShutdown();
        delegate.shutdown(e);
    }

    private void internalShutdown() {
        ackInputSubscription.unsubscribe();
        heartbeatSenderReceiver.unsubscribe();
    }

    @Override
    public Observable<Void> lifecycleObservable() {
        return delegate.lifecycleObservable();
    }

    class HeartbeatSenderReceiver extends Subscriber<Long> {

        private final AtomicInteger missingHeartbeatsCount = new AtomicInteger();

        HeartbeatSenderReceiver() {
            add(Observable.interval(heartbeatIntervalMs, TimeUnit.MILLISECONDS, scheduler).subscribe(this));
        }

        void onHeartbeatReceived() {
            logger.debug("Received heartbeat message from {}", delegate.name());
            missingHeartbeatsCount.decrementAndGet();
        }

        @Override
        public void onCompleted() {
            shutdown();
        }

        @Override
        public void onError(Throwable e) {
            logger.error("Heartbeat receiver subscription got an error. This will close the connection " + delegate.name(), e);
            shutdown(e);
        }

        @Override
        public void onNext(Long aLong) {
            if (missingHeartbeatsCount.incrementAndGet() > tolerance) {
                logger.warn("More than {} heartbeat messages missed; closing the connection {}", tolerance, delegate.name());
                shutdown(MISSING_HEARTBEAT_EXCEPTION);
            } else {
                logger.debug("Sending heartbeat message in the connection {}", delegate.name());
                submit(ProtocolModel.getDefaultModel().newHeartbeat()).subscribe(new Subscriber<Void>() {
                    @Override
                    public void onCompleted() {
                    }

                    @Override
                    public void onError(Throwable e) {
                        logger.warn("Failed to send heartbeat message; terminating the connection " + delegate.name(), e);
                        shutdown(e);
                    }

                    @Override
                    public void onNext(Void aVoid) {
                    }
                });
            }
        }
    }
}
