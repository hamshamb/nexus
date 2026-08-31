/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 hamshamb
 */
package dev.nexus.core.session;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;

/**
 * Holds one session's current state and enforces its legal transitions.
 *
 * <p>Transitions are serialised on this object's monitor so that concurrent callers --
 * the game thread stopping a session while a transport thread reports a failure -- cannot
 * interleave into an impossible state. Listeners are notified outside the lock, because a
 * listener that touches the game or the network must never run while we hold it.
 *
 * @param <S> the state enum for this kind of session
 */
public final class SessionMachine<S extends Enum<S>> {

    private final String name;
    private final BiPredicate<S, S> transitionAllowed;
    private final List<TransitionListener<S>> listeners = new CopyOnWriteArrayList<>();

    private S state;

    /**
     * @param name        identifies this machine in logs and errors
     * @param initial     the starting state
     * @param allowed     decides whether a transition from one state to another is legal
     */
    public SessionMachine(String name, S initial, BiPredicate<S, S> allowed) {
        this.name = Objects.requireNonNull(name, "name");
        this.state = Objects.requireNonNull(initial, "initial");
        this.transitionAllowed = Objects.requireNonNull(allowed, "allowed");
    }

    /** A machine over {@link HostSessionState}, starting at {@link HostSessionState#IDLE}. */
    public static SessionMachine<HostSessionState> forHost() {
        return new SessionMachine<>("host", HostSessionState.IDLE,
                HostSessionState::canTransitionTo);
    }

    /**
     * A machine over {@link GuestSessionState}, starting at
     * {@link GuestSessionState#RESOLVING}.
     */
    public static SessionMachine<GuestSessionState> forGuest() {
        return new SessionMachine<>("guest", GuestSessionState.RESOLVING,
                GuestSessionState::canTransitionTo);
    }

    /** The current state. */
    public synchronized S state() {
        return state;
    }

    /** Whether the machine is currently in {@code expected}. */
    public synchronized boolean isIn(S expected) {
        return state == expected;
    }

    /**
     * Moves to {@code next}, failing if the lifecycle does not permit it.
     *
     * @param reason a short description for logs; may be {@code null}
     * @throws IllegalTransitionException if the transition is not legal
     */
    public void transitionTo(S next, String reason) {
        Objects.requireNonNull(next, "next");
        S previous;
        synchronized (this) {
            if (!transitionAllowed.test(state, next)) {
                throw new IllegalTransitionException(state, next);
            }
            previous = state;
            state = next;
        }
        notifyListeners(previous, next, reason);
    }

    /**
     * Moves to {@code next} only if the lifecycle permits it, reporting whether it did.
     *
     * <p>This is for genuinely racy shutdown paths, where two sources may legitimately
     * both try to stop a session and only one should win. It is not a way to avoid
     * thinking about transitions -- prefer {@link #transitionTo} everywhere else.
     *
     * @return {@code true} if the transition happened
     */
    public boolean tryTransitionTo(S next, String reason) {
        Objects.requireNonNull(next, "next");
        S previous;
        synchronized (this) {
            if (!transitionAllowed.test(state, next)) {
                return false;
            }
            previous = state;
            state = next;
        }
        notifyListeners(previous, next, reason);
        return true;
    }

    /** Registers a listener notified after every successful transition. */
    public void addListener(TransitionListener<S> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(TransitionListener<S> listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(S from, S to, String reason) {
        for (TransitionListener<S> listener : listeners) {
            listener.transitioned(from, to, reason);
        }
    }

    @Override
    public synchronized String toString() {
        return name + "[" + state + "]";
    }

    /**
     * Notified after a session changes state.
     *
     * <p>Called outside the machine's lock, on whichever thread made the transition.
     * Implementations must not block.
     */
    @FunctionalInterface
    public interface TransitionListener<S extends Enum<S>> {
        void transitioned(S from, S to, String reason);
    }
}
