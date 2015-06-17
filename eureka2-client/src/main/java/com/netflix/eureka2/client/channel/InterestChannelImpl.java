package com.netflix.eureka2.client.channel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.netflix.eureka2.channel.AbstractClientChannel;
import com.netflix.eureka2.channel.InterestChannel;
import com.netflix.eureka2.channel.InterestChannel.STATE;
import com.netflix.eureka2.client.interest.BatchingRegistry;
import com.netflix.eureka2.config.SystemConfigLoader;
import com.netflix.eureka2.interests.ChangeNotification;
import com.netflix.eureka2.interests.Interest;
import com.netflix.eureka2.interests.ModifyNotification;
import com.netflix.eureka2.interests.StreamStateNotification;
import com.netflix.eureka2.interests.StreamStateNotification.BufferState;
import com.netflix.eureka2.metric.InterestChannelMetrics;
import com.netflix.eureka2.protocol.common.AddInstance;
import com.netflix.eureka2.protocol.common.DeleteInstance;
import com.netflix.eureka2.protocol.interest.InterestRegistration;
import com.netflix.eureka2.protocol.common.InterestSetNotification;
import com.netflix.eureka2.protocol.common.StreamStateUpdate;
import com.netflix.eureka2.protocol.interest.UpdateInstanceInfo;
import com.netflix.eureka2.registry.Source;
import com.netflix.eureka2.registry.Sourced;
import com.netflix.eureka2.registry.SourcedEurekaRegistry;
import com.netflix.eureka2.registry.instance.Delta;
import com.netflix.eureka2.registry.instance.InstanceInfo;
import com.netflix.eureka2.transport.MessageConnection;
import com.netflix.eureka2.transport.TransportClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.observers.SafeSubscriber;
import rx.subjects.BehaviorSubject;

/**
 * An implementation of {@link InterestChannel}. It is mandatory that all operations
 * on the channel are serialized, by the external client. This class is not thread safe and all operations on it
 * shall be executed by the same thread.
 *
 * Use {@link InterestChannelInvoker} for serializing operations on this channel.
 *
 * @author Nitesh Kant
 */
public class InterestChannelImpl extends AbstractClientChannel<STATE> implements InterestChannel {

    private static final Logger logger = LoggerFactory.getLogger(InterestChannelImpl.class);

    private final BatchingRegistry<InstanceInfo> remoteBatchingRegistry;

    // FIXME: adding this hack for now to deal with a race condition w.r.t. to the remoteBatchingRegistry
    // emitting hints faster than the data can be added to the client side registry. This will go away
    // after the next batch of fixes to refactor the client side registry and index structure, such that
    // batch markers are routed through the indexes instead of asynchronously outside of the indexes.
    private final BehaviorSubject<ChangeNotification<InstanceInfo>> remoteBatchingSubject;
    private final long bufferHintDelayMs = SystemConfigLoader
            .getFromSystemPropertySafe("eureka.hacks.interestChannel.bufferHintDelayMs", 100);
    private final long maxBufferHintDelayMs = SystemConfigLoader
            .getFromSystemPropertySafe("eureka.hacks.interestChannel.maxBufferHintDelayMs", 5000);

    /**
     * Since we assume single threaded access to this channel, no need for concurrency control
     */
    protected Observable<ChangeNotification<InstanceInfo>> channelInterestStream;

    private final AtomicLong delayCounter = new AtomicLong(0l);
    protected Subscriber<ChangeNotification<InstanceInfo>> channelInterestSubscriber;

    protected final SourcedEurekaRegistry<InstanceInfo> registry;
    private final Source selfSource;

    /**
     * A local copy of instances received by this channel from the server. This is used for:
     *
     * <ul>
     <li><i>Updates on the wire</i>: Since we only get the delta on the wire, we use this map to get the last seen
     {@link InstanceInfo} and apply the delta on it to get the new {@link InstanceInfo}</li>
     <li><i>Deletes on the wire</i>: Since we only get the identifier for the instance deleted, we use this map to
     get the last seen {@link InstanceInfo}</li>
     </ul>
     *
     * <h2>Thread safety</h2>
     *
     * Since this channel directly leverages the underlying {@link MessageConnection} and our underlying stack guarantees
     * that there are not concurrent updates sent to the input reader, we can safely assume that this code is single
     * threaded.
     */
    private final Map<String, InstanceInfo> idVsInstance = new HashMap<>();

    public InterestChannelImpl(final SourcedEurekaRegistry<InstanceInfo> registry,
                               BatchingRegistry<InstanceInfo> remoteBatchingRegistry,
                               TransportClient client,
                               long generationId,
                               InterestChannelMetrics metrics) {
        super(STATE.Idle, client, metrics);
        this.remoteBatchingRegistry = remoteBatchingRegistry;
        this.selfSource = new Source(Source.Origin.INTERESTED, "clientInterestChannel", generationId);
        this.registry = registry;
        this.remoteBatchingSubject = BehaviorSubject.create();
        channelInterestSubscriber = new ChannelInterestSubscriber(registry, remoteBatchingSubject);
        channelInterestStream = createInterestStream();

        logger.info("created new interestChannel with source {}", selfSource);
    }

    @Override
    public Source getSource() {
        return selfSource;
    }

    // channel contract means this will be invoked in serial.
    @Override
    public Observable<Void> change(final Interest<InstanceInfo> newInterest) {
        if (state.get() == STATE.Closed) {
            return Observable.error(CHANNEL_CLOSED_EXCEPTION);
        }

        Observable<Void> serverRequest = connect() // Connect is idempotent and does not connect on every call.
                .switchMap(new Func1<MessageConnection, Observable<Void>>() {
                    @Override
                    public Observable<Void> call(MessageConnection serverConnection) {
                        return sendExpectAckOnConnection(serverConnection, new InterestRegistration(newInterest));
                    }
                });

        return Observable.create(new Observable.OnSubscribe<Void>() {
            @Override
            public void call(Subscriber<? super Void> subscriber) {
                if (STATE.Closed == state.get()) {
                    subscriber.onError(CHANNEL_CLOSED_EXCEPTION);
                } else if (moveToState(STATE.Idle, STATE.Open)) {
                    logger.debug("First time registration: {}", newInterest);
                    channelInterestStream.subscribe(channelInterestSubscriber);
                    remoteBatchingRegistry.connectTo(remoteBatchingSubject);

                } else {
                    logger.debug("Channel changes: {}", newInterest);
                }
                remoteBatchingRegistry.retainAll(newInterest);
                subscriber.onCompleted();
            }
        }).concatWith(serverRequest);
    }

    @Override
    public Observable<ChangeNotification<InstanceInfo>> getChangeNotificationStream() {
        return channelInterestStream;
    }

    @Override
    protected void _close() {
        if (state.get() != STATE.Closed) {
            moveToState(state.get(), STATE.Closed);
            idVsInstance.clear();
            super._close();
        }
    }

    protected Observable<ChangeNotification<InstanceInfo>> createInterestStream() {

        return connect().switchMap(new Func1<MessageConnection, Observable<? extends ChangeNotification<InstanceInfo>>>() {
            @Override
            public Observable<? extends ChangeNotification<InstanceInfo>> call(final MessageConnection connection) {
                return connection.incoming().filter(new Func1<Object, Boolean>() {
                    @Override
                    public Boolean call(Object message) {
                        boolean isKnown = message instanceof InterestSetNotification;
                        if (!isKnown) {
                            logger.warn("Unrecognized discovery protocol message of type " + message.getClass());
                        }
                        return isKnown;
                    }
                }).map(new Func1<Object, ChangeNotification<InstanceInfo>>() {
                    @Override
                    public ChangeNotification<InstanceInfo> call(Object message) {
                        ChangeNotification<InstanceInfo> changeNotification;
                        InterestSetNotification notification = (InterestSetNotification) message;
                        if (notification instanceof AddInstance) {
                            changeNotification = addMessageToChangeNotification((AddInstance) notification);
                        } else if (notification instanceof UpdateInstanceInfo) {
                            changeNotification = updateMessageToChangeNotification((UpdateInstanceInfo) notification);
                        } else if (notification instanceof DeleteInstance) {
                            changeNotification = deleteMessageToChangeNotification((DeleteInstance) notification);
                        } else if (notification instanceof StreamStateUpdate) {
                            changeNotification = streamStateUpdateToStreamStateNotification((StreamStateUpdate) notification);
                        } else {
                            throw new IllegalArgumentException("Unknown message received on the interest channel. Type: "
                                    + message.getClass().getName());
                        }

                        sendAckOnConnection(connection);
                        return changeNotification;
                    }
                }).filter(new Func1<ChangeNotification<InstanceInfo>, Boolean>() {
                    @Override
                    public Boolean call(ChangeNotification<InstanceInfo> notification) {
                        return null != notification;
                    }
                });
            }
        }).share();
    }

    /**
     * For an AddInstance msg,
     * - if it does not exist in cache, this is an Add Notification to the store
     *   For simplicity we treat this as an add if the new msg have a GREATER version number,
     *   and ignore it otherwise
     */
    private ChangeNotification<InstanceInfo> addMessageToChangeNotification(AddInstance msg) {
        ChangeNotification<InstanceInfo> notification;

        InstanceInfo incoming = msg.getInstanceInfo();
        InstanceInfo cached = idVsInstance.get(incoming.getId());
        if (cached != null) {
            logger.debug("Received newer version of an existing instanceInfo as Add");
        }
        idVsInstance.put(incoming.getId(), incoming);
        notification = new ChangeNotification<>(ChangeNotification.Kind.Add, incoming);
        return notification;
    }

    /**
     * For an UpdateInstanceInfo msg,
     * - if it does not exist in cache, we ignore this message as we do not have enough information to restore it
     * - if it exist in cache but is different, this is a modify notification to the store.
     *   We only apply changes to cached instance if it has a version number GREATER THAN the cached
     *   version number.
     */
    @SuppressWarnings("unchecked")
    private ChangeNotification<InstanceInfo> updateMessageToChangeNotification(UpdateInstanceInfo msg) {
        ModifyNotification<InstanceInfo> notification = null;

        Delta delta = msg.getDelta();
        InstanceInfo cached = idVsInstance.get(delta.getId());
        if (cached == null) {
            if (logger.isWarnEnabled()) {
                logger.warn("Update notification received for non-existent instance id " + delta.getId());
            }
        } else {
            InstanceInfo updatedInfo = cached.applyDelta(delta);
            idVsInstance.put(updatedInfo.getId(), updatedInfo);
            notification = new ModifyNotification(updatedInfo, Collections.singleton(delta));
        }

        return notification;
    }

    /**
     * For a DeleteInstance msg,
     * - if it does not exist in cache, we ignore this message as it is irrelevant
     * - else just remove it. We handle conflicts with delete and add on the registration side
     */
    private ChangeNotification<InstanceInfo> deleteMessageToChangeNotification(DeleteInstance msg) {
        ChangeNotification<InstanceInfo> notification = null;

        String instanceId = msg.getInstanceId();
        InstanceInfo removedInstance = idVsInstance.remove(instanceId);
        if (removedInstance != null) {
            notification = new ChangeNotification<>(ChangeNotification.Kind.Delete, removedInstance);
        } else if (logger.isWarnEnabled()) {
            logger.warn("Delete notification received for non-existent instance id:" + instanceId);
        }

        return notification;
    }

    private ChangeNotification<InstanceInfo> streamStateUpdateToStreamStateNotification(StreamStateUpdate notification) {
        BufferState state = notification.getState();
        if (state == BufferState.BufferStart || state == BufferState.BufferEnd) {
            return new StreamStateNotification<>(state, notification.getInterest());
        }
        throw new IllegalStateException("Unexpected state " + state);
    }

    protected class ChannelInterestSubscriber extends SafeSubscriber<ChangeNotification<InstanceInfo>> {
        public ChannelInterestSubscriber(
                final SourcedEurekaRegistry<InstanceInfo> registry,
                final BehaviorSubject<ChangeNotification<InstanceInfo>> remoteBatchingSubject
        ) {
            super(new Subscriber<ChangeNotification<InstanceInfo>>() {
                @Override
                public void onCompleted() {
                    // TODO: handle
                    logger.debug("Channel interest completed");
                }

                @Override
                public void onError(Throwable e) {
                    logger.debug("Channel interest throw error", e);  // debug log as errors are retried by default
                }

                @Override
                public void onNext(final ChangeNotification<InstanceInfo> notification) {
                    switch (notification.getKind()) {  // these are in-mem blocking ops
                        case Add:
                        case Modify:
                            registry.register(notification.getData(), selfSource);
                            delayCounter.addAndGet(bufferHintDelayMs);
                            break;
                        case Delete:
                            registry.unregister(notification.getData(), selfSource);
                            delayCounter.addAndGet(bufferHintDelayMs);
                            break;
                        case BufferSentinel:
                            BufferState bufferState = ((StreamStateNotification<InstanceInfo>) notification).getBufferState();
                            if (bufferState == BufferState.BufferEnd) {
                                long delay = Math.min(delayCounter.getAndSet(0), maxBufferHintDelayMs);

                                Observable.timer(delay, TimeUnit.MILLISECONDS)
                                        .doOnNext(new Action1<Long>() {
                                            @Override
                                            public void call(Long aLong) {
                                                remoteBatchingSubject.onNext(notification);
                                            }
                                        }).subscribe();
                            }
                            // No-op
                            break;
                        default:
                            logger.error("Unrecognized notification kind");
                    }
                }
            });
        }
    }
}
