package dev.ermc.bridge;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Invisible stand-in for the host game's (Elden Ring's) ground. Players collide with it, can
 * place blocks against it, and entity shadows land on it, but it renders nothing, so the host
 * game's own terrain shows through. HEIGHT (1-16) lets a voxel represent ground at 1/16-block steps.
 */
public class TerrainBlock extends Block {
	public static final MapCodec<TerrainBlock> CODEC = simpleCodec(TerrainBlock::new);
	public static final IntegerProperty HEIGHT = IntegerProperty.create("height", 1, 16);
	private static final VoxelShape[] SHAPES = new VoxelShape[17];

	static {
		for (int i = 1; i <= 16; i++) {
			SHAPES[i] = Block.box(0, 0, 0, 16, i, 16);
		}
	}

	public TerrainBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(HEIGHT, 16));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(HEIGHT);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES[state.getValue(HEIGHT)];
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		// MODEL with an empty model: draws nothing, but unlike INVISIBLE it still receives
		// entity blob shadows, which visually grounds Minecraft entities on the host game's terrain.
		return RenderShape.MODEL;
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
		return true;
	}

	@Override
	protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		return 1.0F;
	}

	@Override
	protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
		// Ground in the lower half of this voxel: let placed blocks take the voxel so they
		// sink slightly into the host game's terrain instead of floating above it.
		return state.getValue(HEIGHT) <= 8;
	}
}
