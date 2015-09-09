package com.jivesoftware.os.amza.aquarium;

import com.jivesoftware.os.amza.aquarium.ReadWaterlineTx.Tx;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;

/**
 * @author jonathan.colt
 */
public class Aquarium {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final OrderIdProvider versionProvider;
    private final CurrentTimeMillis currentTimeMillis;
    private final ReadWaterlineTx waterlineTx;
    private final TransitionQuorum transitionCurrent;
    private final TransitionQuorum transitionDesired;
    private final Member member;
    private final AwaitLivelyEndState awaitLivelyEndState;

    public Aquarium(OrderIdProvider versionProvider,
        CurrentTimeMillis currentTimeMillis,
        ReadWaterlineTx waterlineTx,
        TransitionQuorum transitionCurrent,
        TransitionQuorum transitionDesired,
        Member member,
        AwaitLivelyEndState awaitLivelyEndState) {
        this.versionProvider = versionProvider;
        this.currentTimeMillis = currentTimeMillis;
        this.waterlineTx = waterlineTx;
        this.transitionCurrent = transitionCurrent;
        this.transitionDesired = transitionDesired;
        this.member = member;
        this.awaitLivelyEndState = awaitLivelyEndState;
    }

    public void inspectState(Member member, Tx tx) throws Exception {
        waterlineTx.tx(member, tx);
    }

    public void tapTheGlass() throws Exception {
        waterlineTx.tx(member, (current, desired) -> {
            current.acknowledgeOther();
            desired.acknowledgeOther();

            awaitLivelyEndState.notifyChange(() -> {
                Waterline currentWaterline = current.get();
                if (currentWaterline == null) {
                    currentWaterline = new Waterline(member, State.bootstrap, versionProvider.nextId(), -1L, true, Long.MAX_VALUE);
                }
                Waterline desiredWaterline = desired.get();
                //LOG.info("Tap {} current:{} desired:{}", member, currentWaterline, desiredWaterline);

                boolean advanced = currentWaterline.getState().transistor.advance(currentTimeMillis,
                    currentWaterline,
                    current,
                    transitionCurrent,
                    desiredWaterline,
                    desired,
                    transitionDesired);
                return advanced || (desiredWaterline != null && desiredWaterline.isAtQuorum() && desiredWaterline.isAlive(currentTimeMillis.get()));
            });

            return true;
        });
    }

    /**
     * @return null, leader or follower
     */
    public Waterline livelyEndState() throws Exception {
        Waterline[] waterline = {null};
        waterlineTx.tx(member, (current, desired) -> {

            Waterline currentWaterline = current.get();
            Waterline desiredWaterline = desired.get();

            if (currentWaterline != null
                && currentWaterline.isAlive(currentTimeMillis.get())
                && currentWaterline.isAtQuorum()
                && State.checkEquals(currentTimeMillis, currentWaterline, desiredWaterline)) {

                if (desiredWaterline.getState() == State.leader) {
                    waterline[0] = desiredWaterline;
                }
                if (desiredWaterline.getState() == State.follower) {
                    waterline[0] = desiredWaterline;
                }
            }
            return true;
        });
        return waterline[0];
    }

    public Waterline getLeader() throws Exception {
        Waterline[] leader = {null};
        waterlineTx.tx(member, (current, desired) -> {
            leader[0] = State.highest(currentTimeMillis, State.leader, desired, desired.get());
            return true;
        });
        return leader[0];
    }

    public Waterline awaitLivelyEndState(long timeoutMillis) throws Exception {
        return awaitLivelyEndState.awaitChange(this::livelyEndState, timeoutMillis);
    }

    public Waterline getState(Member member) throws Exception {
        Waterline[] state = new Waterline[1];
        waterlineTx.tx(member, (readCurrent, readDesired) -> {
            Waterline current = readCurrent.get();
            if (current == null) {
                state[0] = new Waterline(member, State.bootstrap, -1, -1, false, -1);
            } else {
                state[0] = current;
            }
            return true;
        });
        return state[0];
    }

    public void expunge(Member member) throws Exception {
        waterlineTx.tx(member, (readCurrent, readDesired) -> {
            transitionDesired.transition(readDesired.get(), versionProvider.nextId(), State.expunged);
            return true;
        });
        tapTheGlass();
    }

}
