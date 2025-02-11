/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.me.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.SortedSet;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;

import it.unimi.dsi.fastutil.objects.ObjectRBTreeSet;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IGridServiceProvider;
import appeng.api.networking.IGridStorage;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.energy.IEnergyWatcher;
import appeng.api.networking.energy.IEnergyWatcherNode;
import appeng.api.networking.events.GridPowerIdleChange;
import appeng.api.networking.events.GridPowerStatusChange;
import appeng.api.networking.events.GridPowerStorageStateChanged;
import appeng.api.networking.events.GridPowerStorageStateChanged.PowerEventType;
import appeng.api.networking.pathing.IPathingService;
import appeng.me.Grid;
import appeng.me.GridNode;
import appeng.me.energy.EnergyThreshold;
import appeng.me.energy.EnergyWatcher;
import appeng.me.energy.IEnergyGridProvider;

public class EnergyService implements IEnergyService, IGridServiceProvider {

    static {
        GridHelper.addGridServiceEventHandler(GridPowerIdleChange.class, IEnergyService.class,
                (service, event) -> {
                    ((EnergyService) service).nodeIdlePowerChangeHandler(event);
                });

        GridHelper.addGridServiceEventHandler(GridPowerStorageStateChanged.class,
                IEnergyService.class,
                (service, event) -> {
                    ((EnergyService) service).storagePowerChangeHandler(event);
                });
    }

    private static final double MAX_BUFFER_STORAGE = 800;

    private static final Comparator<IAEPowerStorage> COMPARATOR_HIGHEST_PRIORITY_FIRST = (o1, o2) -> {
        final int cmp = Integer.compare(o2.getPriority(), o1.getPriority());
        return cmp != 0 ? cmp : Integer.compare(System.identityHashCode(o2), System.identityHashCode(o1));
    };

    private static final Comparator<IAEPowerStorage> COMPARATOR_LOWEST_PRIORITY_FIRST = (o1,
            o2) -> -COMPARATOR_HIGHEST_PRIORITY_FIRST.compare(o1, o2);

    private final NavigableSet<EnergyThreshold> interests = Sets.newTreeSet();
    private final double averageLength = 40.0;

    // Should only be modified from the add/remove methods below to guard against
    // concurrent modifications
    private final SortedSet<IAEPowerStorage> providers = new ObjectRBTreeSet<>(COMPARATOR_HIGHEST_PRIORITY_FIRST);
    // Used to track whether an extraction is currently in progress, to fail fast
    // when something externally
    // modifies the energy grid.
    private boolean ongoingExtractOperation = false;

    // Should only be modified from the add/remove methods below to guard against
    // concurrent modifications
    private final SortedSet<IAEPowerStorage> requesters = new ObjectRBTreeSet<>(COMPARATOR_LOWEST_PRIORITY_FIRST);
    // Used to track whether an injection is currently in progress, to fail fast
    // when something externally
    // modifies the energy grid.
    private boolean ongoingInjectOperation = false;

    private final Multiset<IEnergyGridProvider> energyGridProviders = HashMultiset.create();
    final Grid grid;
    private final HashMap<IGridNode, IEnergyWatcher> watchers = new HashMap<>();

    /**
     * estimated power available.
     */
    private int availableTicksSinceUpdate = 0;
    private double globalAvailablePower = 0;
    private double globalMaxPower = MAX_BUFFER_STORAGE;

    /**
     * idle draw.
     */
    private double drainPerTick = 0;
    private double avgDrainPerTick = 0;
    private double avgInjectionPerTick = 0;
    private double tickDrainPerTick = 0;
    private double tickInjectionPerTick = 0;

    /**
     * power status
     */
    private boolean publicHasPower = false;
    private boolean hasPower = true;
    private long ticksSinceHasPowerChange = 900;

    private final PathingService pgc;
    private double lastStoredPower = -1;

    private final GridPowerStorage localStorage = new GridPowerStorage();

    /**
     * The overlay grid containing all the energy services of grids that may be connected by parts like
     * {@linkplain appeng.parts.networking.QuartzFiberPart quartz fibers}.
     */
    EnergyOverlayGrid overlayGrid = null;

    public EnergyService(IGrid g, IPathingService pgc) {
        this.grid = (Grid) g;
        this.pgc = (PathingService) pgc;
        this.requesters.add(this.localStorage);
        this.providers.add(this.localStorage);
    }

    public void nodeIdlePowerChangeHandler(GridPowerIdleChange ev) {
        // update power usage based on event.
        final var node = (GridNode) ev.node;

        final double newDraw = node.getIdlePowerUsage();
        final double diffDraw = newDraw - node.getPreviousDraw();
        node.setPreviousDraw(newDraw);

        this.drainPerTick += diffDraw;
    }

    public void storagePowerChangeHandler(GridPowerStorageStateChanged ev) {
        if (ev.storage.isAEPublicPowerStorage()) {
            switch (ev.type) {
                case PROVIDE_POWER:
                    if (ev.storage.getPowerFlow() != AccessRestriction.WRITE) {
                        addProvider(ev.storage);
                    }
                    break;
                case REQUEST_POWER:
                    if (ev.storage.getPowerFlow() != AccessRestriction.READ) {
                        addRequester(ev.storage);
                    }
                    break;
            }
        } else {
            new RuntimeException("Attempt to ask the IEnergyGrid to charge a non public energy store.")
                    .printStackTrace();
        }
    }

    @Override
    public void onServerEndTick() {
        if (!this.interests.isEmpty()) {
            final double oldPower = this.lastStoredPower;
            this.lastStoredPower = this.getStoredPower();

            final EnergyThreshold low = new EnergyThreshold(Math.min(oldPower, this.lastStoredPower),
                    Integer.MIN_VALUE);
            final EnergyThreshold high = new EnergyThreshold(Math.max(oldPower, this.lastStoredPower),
                    Integer.MAX_VALUE);

            for (EnergyThreshold th : this.interests.subSet(low, true, high, true)) {
                ((EnergyWatcher) th.getEnergyWatcher()).post(this);
            }
        }

        this.avgDrainPerTick *= (this.averageLength - 1) / this.averageLength;
        this.avgInjectionPerTick *= (this.averageLength - 1) / this.averageLength;

        this.avgDrainPerTick += this.tickDrainPerTick / this.averageLength;
        this.avgInjectionPerTick += this.tickInjectionPerTick / this.averageLength;

        this.tickDrainPerTick = 0;
        this.tickInjectionPerTick = 0;

        // power information.
        boolean currentlyHasPower = false;

        if (this.drainPerTick > 0.0001) {
            final double drained = this.extractAEPower(this.getIdlePowerUsage(), Actionable.MODULATE,
                    PowerMultiplier.CONFIG);
            currentlyHasPower = drained >= this.drainPerTick - 0.001;
        } else {
            currentlyHasPower = this.extractAEPower(0.1, Actionable.SIMULATE, PowerMultiplier.CONFIG) > 0;
        }

        // ticks since change..
        if (currentlyHasPower == this.hasPower) {
            this.ticksSinceHasPowerChange++;
        } else {
            this.ticksSinceHasPowerChange = 0;
        }

        // update status..
        this.hasPower = currentlyHasPower;

        // update public status, this buffers power ups for 30 ticks.
        if (this.hasPower && this.ticksSinceHasPowerChange > 30) {
            this.publicPowerState(true, this.grid);
        } else if (!this.hasPower) {
            this.publicPowerState(false, this.grid);
        }

        this.availableTicksSinceUpdate++;
    }

    @Override
    public double extractAEPower(double amt, Actionable mode, PowerMultiplier pm) {
        final double toExtract = pm.multiply(amt);
        double extracted = 0;

        for (EnergyService service : getConnectedServices()) {
            extracted += service.extractProviderPower(toExtract - extracted, mode);

            if (extracted >= toExtract) {
                break;
            }
        }

        return pm.divide(extracted);
    }

    @Override
    public double getIdlePowerUsage() {
        return this.drainPerTick + this.pgc.getChannelPowerUsage();
    }

    @Override
    public double getChannelPowerUsage() {
        return this.pgc.getChannelPowerUsage();
    }

    private void publicPowerState(boolean newState, IGrid grid) {
        if (this.publicHasPower == newState) {
            return;
        }

        this.publicHasPower = newState;
        this.grid.setImportantFlag(0, this.publicHasPower);
        grid.postEvent(new GridPowerStatusChange());

        this.grid.notifyAllNodes(IGridNodeListener.State.POWER);
    }

    /**
     * refresh current stored power.
     */
    private void refreshPower() {
        this.availableTicksSinceUpdate = 0;
        this.globalAvailablePower = 0;
        for (IAEPowerStorage p : this.providers) {
            this.globalAvailablePower += p.getAECurrentPower();
        }
    }

    public Collection<IEnergyGridProvider> providers() {
        return this.energyGridProviders;
    }

    public double extractProviderPower(double amt, Actionable mode) {
        double extractedPower = 0;

        final Iterator<IAEPowerStorage> it = this.providers.iterator();

        ongoingExtractOperation = true;
        try {
            while (extractedPower < amt && it.hasNext()) {
                final IAEPowerStorage node = it.next();

                final double req = amt - extractedPower;
                final double newPower = node.extractAEPower(req, mode, PowerMultiplier.ONE);
                extractedPower += newPower;

                if (newPower < req && mode == Actionable.MODULATE) {
                    it.remove();
                }
            }
        } finally {
            ongoingExtractOperation = false;
        }

        final double result = Math.min(extractedPower, amt);

        if (mode == Actionable.MODULATE) {
            if (extractedPower > amt) {
                this.localStorage.addCurrentAEPower(extractedPower - amt);
            }

            this.globalAvailablePower -= result;
            this.tickDrainPerTick += result;
        }

        return result;
    }

    public double injectProviderPower(double amt, Actionable mode) {
        final double originalAmount = amt;

        final Iterator<IAEPowerStorage> it = this.requesters.iterator();

        ongoingInjectOperation = true;
        try {
            while (amt > 0 && it.hasNext()) {
                final IAEPowerStorage node = it.next();
                amt = node.injectAEPower(amt, mode);

                if (amt > 0 && mode == Actionable.MODULATE) {
                    it.remove();
                }
            }
        } finally {
            ongoingInjectOperation = false;
        }

        final double overflow = Math.max(0.0, amt);

        if (mode == Actionable.MODULATE) {
            this.tickInjectionPerTick += originalAmount - overflow;
        }

        return overflow;
    }

    public double getProviderEnergyDemand(double maxRequired) {
        double required = 0;

        final Iterator<IAEPowerStorage> it = this.requesters.iterator();
        while (required < maxRequired && it.hasNext()) {
            final IAEPowerStorage node = it.next();
            if (node.getPowerFlow() != AccessRestriction.READ) {
                required += Math.max(0.0, node.getAEMaxPower() - node.getAECurrentPower());
            }
        }

        return required;
    }

    @Override
    public double getAvgPowerUsage() {
        return this.avgDrainPerTick;
    }

    @Override
    public double getAvgPowerInjection() {
        return this.avgInjectionPerTick;
    }

    @Override
    public boolean isNetworkPowered() {
        return this.publicHasPower;
    }

    @Override
    public double injectPower(double amt, Actionable mode) {
        double leftover = amt;

        for (EnergyService service : getConnectedServices()) {
            leftover = service.injectProviderPower(leftover, mode);

            if (leftover <= 0) {
                break;
            }
        }

        return leftover;
    }

    @Override
    public double getStoredPower() {
        if (this.availableTicksSinceUpdate > 90) {
            this.refreshPower();
        }

        return Math.max(0.0, this.globalAvailablePower);
    }

    @Override
    public double getMaxStoredPower() {
        return this.globalMaxPower;
    }

    @Override
    public double getEnergyDemand(double maxRequired) {
        double required = 0;

        for (EnergyService service : getConnectedServices()) {
            required += service.getProviderEnergyDemand(maxRequired - required);

            if (required >= maxRequired) {
                break;
            }
        }

        return required;
    }

    @Override
    public void removeNode(IGridNode node) {
        var gridProvider = node.getService(IEnergyGridProvider.class);
        if (gridProvider != null) {
            this.energyGridProviders.remove(gridProvider);
            invalidatePowerGraph();
        }

        // idle draw.
        final GridNode gridNode = (GridNode) node;
        this.drainPerTick -= gridNode.getPreviousDraw();

        // power storage.
        var ps = node.getService(IAEPowerStorage.class);
        if (ps != null) {
            if (ps.isAEPublicPowerStorage()) {
                if (ps.getPowerFlow() != AccessRestriction.WRITE) {
                    this.globalMaxPower -= ps.getAEMaxPower();
                    this.globalAvailablePower -= ps.getAECurrentPower();
                }

                removeProvider(ps);
                removeRequester(ps);
            }
        }

        var watcher = this.watchers.remove(node);
        if (watcher != null) {
            watcher.reset();
        }
    }

    private void addRequester(IAEPowerStorage requester) {
        Preconditions.checkState(!ongoingInjectOperation,
                "Cannot modify energy requesters while energy is being injected.");
        this.requesters.add(requester);
    }

    private void removeRequester(IAEPowerStorage requester) {
        Preconditions.checkState(!ongoingInjectOperation,
                "Cannot modify energy requesters while energy is being injected.");
        this.requesters.remove(requester);
    }

    private void addProvider(IAEPowerStorage provider) {
        Preconditions.checkState(!ongoingExtractOperation,
                "Cannot modify energy providers while energy is being extracted.");
        this.providers.add(provider);
    }

    private void removeProvider(IAEPowerStorage provider) {
        Preconditions.checkState(!ongoingExtractOperation,
                "Cannot modify energy providers while energy is being extracted.");
        this.providers.remove(provider);
    }

    @Override
    public void addNode(IGridNode node) {
        var gridProvider = node.getService(IEnergyGridProvider.class);
        if (gridProvider != null) {
            this.energyGridProviders.add(gridProvider);
            invalidatePowerGraph();
        }

        // idle draw...
        final GridNode gridNode = (GridNode) node;
        gridNode.setPreviousDraw(node.getIdlePowerUsage());
        this.drainPerTick += gridNode.getPreviousDraw();

        // power storage
        var ps = node.getService(IAEPowerStorage.class);
        if (ps != null) {
            if (ps.isAEPublicPowerStorage()) {
                final double max = ps.getAEMaxPower();
                final double current = ps.getAECurrentPower();

                if (ps.getPowerFlow() != AccessRestriction.WRITE) {
                    this.globalMaxPower += ps.getAEMaxPower();
                }

                if (current > 0 && ps.getPowerFlow() != AccessRestriction.WRITE) {
                    this.globalAvailablePower += current;
                    addProvider(ps);
                }

                if (current < max && ps.getPowerFlow() != AccessRestriction.READ) {
                    addRequester(ps);
                }
            }
        }

        var ews = node.getService(IEnergyWatcherNode.class);
        if (ews != null) {
            var iw = new EnergyWatcher(this, ews);
            this.watchers.put(node, iw);
            ews.updateWatcher(iw);
        }
    }

    @Override
    public void onSplit(IGridStorage storageB) {
        final double newBuffer = this.localStorage.getAECurrentPower() / 2;
        this.localStorage.removeCurrentAEPower(newBuffer);
        storageB.dataObject().putDouble("buffer", newBuffer);
    }

    @Override
    public void onJoin(IGridStorage storageB) {
        this.localStorage.addCurrentAEPower(storageB.dataObject().getDouble("buffer"));
    }

    @Override
    public void populateGridStorage(IGridStorage storage) {
        storage.dataObject().putDouble("buffer", this.localStorage.getAECurrentPower());
    }

    public boolean registerEnergyInterest(EnergyThreshold threshold) {
        return this.interests.add(threshold);
    }

    public boolean unregisterEnergyInterest(EnergyThreshold threshold) {
        return this.interests.remove(threshold);
    }

    public void invalidatePowerGraph() {
        if (this.overlayGrid != null) {
            this.overlayGrid.invalidate();
        }
    }

    private List<EnergyService> getConnectedServices() {
        if (this.overlayGrid == null) {
            EnergyOverlayGrid.buildCache(this);
        }

        return this.overlayGrid.energyServices;
    }

    private class GridPowerStorage implements IAEPowerStorage {
        private double stored = 0;

        @Override
        public double extractAEPower(double amt, Actionable mode, PowerMultiplier usePowerMultiplier) {
            double extracted = Math.min(amt, this.stored);

            if (mode == Actionable.MODULATE) {
                this.removeCurrentAEPower(extracted);
            }

            return extracted;
        }

        @Override
        public boolean isAEPublicPowerStorage() {
            return true;
        }

        @Override
        public double injectAEPower(double amt, Actionable mode) {
            double toStore = Math.min(amt, MAX_BUFFER_STORAGE - this.stored);

            if (mode == Actionable.MODULATE) {
                this.addCurrentAEPower(toStore);
            }

            return amt - toStore;
        }

        @Override
        public AccessRestriction getPowerFlow() {
            return AccessRestriction.READ_WRITE;
        }

        @Override
        public double getAEMaxPower() {
            return MAX_BUFFER_STORAGE;
        }

        @Override
        public double getAECurrentPower() {
            return this.stored;
        }

        @Override
        public int getPriority() {
            // MIN_VALUE to push it to the back
            return Integer.MIN_VALUE;
        }

        private void addCurrentAEPower(double amount) {
            this.stored += amount;

            if (this.stored > 0.01) {
                EnergyService.this.grid
                        .postEvent(new GridPowerStorageStateChanged(this, PowerEventType.PROVIDE_POWER));
            }
        }

        private void removeCurrentAEPower(double amount) {
            this.stored -= amount;

            if (this.stored < MAX_BUFFER_STORAGE - 0.001) {
                EnergyService.this.grid
                        .postEvent(new GridPowerStorageStateChanged(this, PowerEventType.REQUEST_POWER));
            }
        }
    }
}
