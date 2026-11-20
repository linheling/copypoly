package com.caca.copypoly;

import com.simibubi.create.AllItems;
import com.simibubi.create.content.decoration.copycat.*;
import net.minecraft.core.*;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.Nullable;
import java.util.List;

public class PolyBlock extends CopycatBlock {
    public PolyBlock(Properties props) { super(props); }
    private boolean canWrench(UseOnContext context) {
        Player player = context.getPlayer();
        return player != null && player.mayBuild()
            && context.getLevel().mayInteract(player, context.getClickedPos());
    }
    @Override public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        if (!canWrench(context)) return InteractionResult.FAIL;
        if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
        return super.onWrenched(state, context);
    }
    @Override public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        if (!canWrench(context)) return InteractionResult.FAIL;
        if (!(context.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return InteractionResult.SUCCESS;
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        var event = new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(level, pos, state, player);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return InteractionResult.SUCCESS;

        // Check protection before either refunding the material or removing the block.
        super.onWrenched(state, context);
        if (!player.isCreative()) {
            var drops = net.minecraft.world.level.block.Block.getDrops(state, level, pos, level.getBlockEntity(pos),
                player, context.getItemInHand());
            for (ItemStack drop : drops) player.getInventory().placeItemBackInInventory(drop);
        }
        state.spawnAfterBreak(level, pos, ItemStack.EMPTY, true);
        level.destroyBlock(pos, false);
        com.simibubi.create.content.equipment.wrench.IWrenchable.playRemoveSound(level, pos);
        return InteractionResult.SUCCESS;
    }
    @Override public BlockEntityType<? extends CopycatBlockEntity> getBlockEntityType() { return Copypoly.ENTITY.get(); }
    /** Axis index used by {@link com.caca.copypoly.geometry.Polyhedron} (0=x, 1=y, 2=z). */
    private static int axis(Direction direction) { return direction.getAxis().ordinal(); }
    private static boolean boundary(PolyBlockEntity be, Direction side) {
        return be.poly().faceAtBoundary(axis(side), side.getAxisDirection() == Direction.AxisDirection.POSITIVE);
    }
    /**
     * The material model's connected-texture pass asks this for every position it wants to look at.
     * Only sides where the hull actually shows a face on the block border count, so a partial hull does
     * not pick up connections it has no geometry for. Diagonal offsets are allowed on purpose: corner
     * tiles are probed at {@code pos.relative(a).relative(b)} and would otherwise always read as air.
     */
    @Override public boolean canConnectTexturesToward(BlockAndTintGetter world, BlockPos from, BlockPos to, BlockState state) {
        int dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        return world.getBlockEntity(from) instanceof PolyBlockEntity be && be.poly().showsOffset(dx, dy, dz);
    }
    /**
     * Drives {@link CopycatBlock#getAppearance}: returning {@code false} makes this block report the
     * copied material instead of itself, which is what lets other connected-texture blocks join up to it.
     */
    @Override public boolean isIgnoredConnectivitySide(BlockAndTintGetter world, BlockState state, Direction side, BlockPos from, @Nullable BlockPos to) {
        if (to == null) return true;
        if (!(world.getBlockEntity(from) instanceof PolyBlockEntity be) || !boundary(be, side)) return true;
        return world.getBlockState(to).isAir();
    }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter w, BlockPos p, CollisionContext c) {
        return w.getBlockEntity(p) instanceof PolyBlockEntity be ? be.shape() : Shapes.block();
    }
    @Override protected VoxelShape getCollisionShape(BlockState s, BlockGetter w, BlockPos p, CollisionContext c) { return getShape(s,w,p,c); }
    @Override protected VoxelShape getOcclusionShape(BlockState s, BlockGetter w, BlockPos p) { return Shapes.empty(); }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) { return false; }
    @Override public boolean addLandingEffects(BlockState state, ServerLevel level, BlockPos pos, BlockState landedState,
                                               LivingEntity entity, int numberOfParticles) {
        if (!(level.getBlockEntity(pos) instanceof PolyBlockEntity be) || !be.hasCustomMaterial()) return false;
        BlockState material = be.getMaterial();
        if (material.addLandingEffects(level, pos, material, entity, numberOfParticles)) return true;

        // Match vanilla's edge-landing adjustment and use the entity's actual landing height.
        double x = entity.getX(), z = entity.getZ();
        BlockPos entityPos = entity.blockPosition();
        if (pos.getX() != entityPos.getX() || pos.getZ() != entityPos.getZ()) {
            double dx = x - pos.getX() - 0.5, dz = z - pos.getZ() - 0.5;
            double extent = Math.max(Math.abs(dx), Math.abs(dz));
            x = pos.getX() + 0.5 + dx / extent * 0.5;
            z = pos.getZ() + 0.5 + dz / extent * 0.5;
        }
        // Send the material state itself: particle packets do not carry the copycat's model data.
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, material).setPos(pos),
            x, entity.getY(), z, numberOfParticles, 0.0, 0.0, 0.0, 0.15F);
        return true;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.isShiftKeyDown()) return InteractionResult.PASS;
        openEditor(level, pos, player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                       Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.is(Tags.Items.TOOLS_WRENCH)) {
            if (!player.mayBuild() || !level.mayInteract(player, pos)) return ItemInteractionResult.FAIL;
            InteractionResult result = AllItems.WRENCH.get().useOn(new UseOnContext(player,hand,hit));
            return result.consumesAction() ? ItemInteractionResult.SUCCESS : ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        if (player.isShiftKeyDown()) {
            openEditor(level, pos, player);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!player.mayBuild() || !level.mayInteract(player, pos)) return ItemInteractionResult.FAIL;
        return super.useItemOn(stack,state,level,pos,player,hand,hit);
    }
    public void openEditor(Level level, BlockPos pos, Player player) {
        if (player instanceof ServerPlayer sp && player.mayBuild() && level.mayInteract(player,pos)
                && level.getBlockEntity(pos) instanceof PolyBlockEntity be) {
            sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new PolyMenu(id, inv, pos, be.poly(), be.revision()),
                Component.translatable("screen.copypoly.title")), buf -> {
                    buf.writeBlockPos(pos); buf.writeVarIntArray(be.poly().packed()); buf.writeVarInt(be.revision());
                });
        }
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide) {
            if (level.getBlockEntity(pos) instanceof PolyBlockEntity be) be.setPoly(PolyItem.readShape(stack));
            super.setPlacedBy(level,pos,state,placer,stack);
        }
    }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state,params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof PolyBlockEntity be)
            for (ItemStack stack : drops) if (stack.is(asItem())) PolyItem.saveShape(stack, be.poly());
        return drops;
    }
    @Override public ItemStack getCloneItemStack(BlockState state, HitResult hit, LevelReader level, BlockPos pos, Player player) {
        ItemStack stack = super.getCloneItemStack(state,hit,level,pos,player);
        if (stack.is(asItem()) && level.getBlockEntity(pos) instanceof PolyBlockEntity be) PolyItem.saveShape(stack,be.poly());
        return stack;
    }
}
