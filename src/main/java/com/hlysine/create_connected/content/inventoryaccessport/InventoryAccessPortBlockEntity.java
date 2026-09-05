package com.hlysine.create_connected.content.inventoryaccessport;

import com.simibubi.create.api.packager.InventoryIdentifier;
import com.simibubi.create.content.logistics.packager.IdentifiedInventory;
import com.simibubi.create.content.redstone.DirectedDirectionalBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.CapManipulationBehaviourBase;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.function.Supplier;

import static com.hlysine.create_connected.content.inventoryaccessport.InventoryAccessPortBlock.ATTACHED;

public class InventoryAccessPortBlockEntity extends SmartBlockEntity {
    protected LazyOptional<IItemHandler> itemCapability;
    private InvManipulationBehaviour observedInventory;
    private boolean powered;

    private IItemHandler cachedHandler;
    private boolean handlerDirty = true;

    public InventoryAccessPortBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);

        itemCapability = LazyOptional.empty();
        powered = false;
    }

    @Override
    public void initialize() {
        super.initialize();
        updateConnectedInventory();
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        CapManipulationBehaviourBase.InterfaceProvider towardBlockFacing =
                (w, p, s) -> new BlockFace(p, DirectedDirectionalBlock.getTargetDirection(s));
        behaviours.add(observedInventory = new InvManipulationBehaviour(this, towardBlockFacing) {
            private LazyOptional<IItemHandler> listenedCapability = LazyOptional.empty();

            @Override
            public void findNewCapability() {
                super.findNewCapability();
                handlerDirty = true;

                if (targetCapability.isPresent() && targetCapability != listenedCapability) {
                    listenedCapability = targetCapability;
                    WeakReference<InventoryAccessPortBlockEntity> owner =
                            new WeakReference<>(InventoryAccessPortBlockEntity.this);

                    targetCapability.addListener(ignored -> {
                        InventoryAccessPortBlockEntity be = owner.get();
                        if (be != null) {
                            be.cachedHandler = null;
                            be.handlerDirty = true;
                        }
                    });
                }
            }
        });
    }

    public boolean isAttached() {
        return !powered && observedInventory.hasInventory() && !(observedInventory.getInventory() instanceof WrappedItemHandler);
    }

    public @Nullable BlockState getAttachedBlock() {
        if (!isAttached()) return null;
        return level.getBlockState(observedInventory.getTarget().getConnectedPos());
    }

    public void updateConnectedInventory() {
        observedInventory.findNewCapability();
        handlerDirty = true;
        boolean previouslyPowered = powered;
        assert level != null;
        powered = level.hasNeighborSignal(worldPosition);
        if (powered != previouslyPowered) {
            notifyUpdate();
        }
        if (isAttached() != getBlockState().getValue(ATTACHED)) {
            BlockState state = getBlockState().cycle(ATTACHED);
            level.setBlockAndUpdate(worldPosition, state);
        }
    }

    @Nullable
    public InventoryIdentifier getInventoryId() {
        if (!isAttached()) return null;
        IdentifiedInventory inv = observedInventory.getIdentifiedInventory();
        return inv == null ? null : inv.identifier();
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (isItemHandlerCap(cap)) {
            initCapability();
            return itemCapability.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    protected void read(CompoundTag compound, boolean clientPacket) {
        super.read(compound, clientPacket);
        powered = compound.getBoolean("Powered");
    }

    @Override
    protected void write(CompoundTag compound, boolean clientPacket) {
        super.write(compound, clientPacket);
        compound.putBoolean("Powered", powered);
    }

    private IItemHandler getConnectedItemHandler() {
        if (powered) return null;
        if (handlerDirty) {
            IItemHandler h = observedInventory.getInventory();
            cachedHandler = (h instanceof WrappedItemHandler) ? null : h;
            handlerDirty = false;
        }
        return cachedHandler;
    }

    private void initCapability() {
        if (itemCapability.isPresent()) return;
        itemCapability = LazyOptional.of(InventoryAccessHandler::new);
    }

    @Override
    public void invalidate() {
        super.invalidate();
        cachedHandler = null;
        handlerDirty = true;
        itemCapability.invalidate();
    }

    private class InventoryAccessHandler implements WrappedItemHandler {

        private static final ThreadLocal<Boolean> RECURSION_GUARD = ThreadLocal.withInitial(() -> false);

        private <T> T preventRecursion(Supplier<T> value, T defaultValue) {
            if (RECURSION_GUARD.get()) return defaultValue;

            RECURSION_GUARD.set(true);
            try {
                return value.get();
            } finally {
                RECURSION_GUARD.remove();
            }
        }

        @Override
        public int getSlots() {
            return preventRecursion(() -> {
                IItemHandler handler = getConnectedItemHandler();
                return handler == null ? 0 : handler.getSlots();
            }, 0);
        }

        @Override
        public @NotNull ItemStack getStackInSlot(int i) {
            return preventRecursion(() -> {
                IItemHandler handler = getConnectedItemHandler();
                return handler == null ? ItemStack.EMPTY : handler.getStackInSlot(i);
            }, ItemStack.EMPTY);
        }

        @Override
        public @NotNull ItemStack insertItem(int i, @NotNull ItemStack itemStack, boolean b) {
            return preventRecursion(() -> {
                IItemHandler handler = getConnectedItemHandler();
                return handler == null ? itemStack : handler.insertItem(i, itemStack, b);
            }, itemStack);
        }

        @Override
        public @NotNull ItemStack extractItem(int i, int i1, boolean b) {
            return preventRecursion(() -> {
                IItemHandler handler = getConnectedItemHandler();
                return handler == null ? ItemStack.EMPTY : handler.extractItem(i, i1, b);
            }, ItemStack.EMPTY);
        }

        @Override
        public int getSlotLimit(int i) {
            return preventRecursion(() -> {
                IItemHandler handler = getConnectedItemHandler();
                return handler == null ? 0 : handler.getSlotLimit(i);
            }, 0);
        }

        @Override
        public boolean isItemValid(int i, @NotNull ItemStack itemStack) {
            return preventRecursion(() -> {
                IItemHandler handler = getConnectedItemHandler();
                return handler != null && handler.isItemValid(i, itemStack);
            }, false);
        }
    }
}
