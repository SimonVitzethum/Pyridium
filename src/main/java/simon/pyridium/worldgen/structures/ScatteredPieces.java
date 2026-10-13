// GENERIERT von tools/port_scattered.py aus den Bauanweisungen der drei Strukturen; per Hand pflegbar.
package simon.pyridium.worldgen.structures;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

/** Vorlagen-Bauer der drei Streu-Strukturen (Wuestenpyramide, Dschungeltempel, Sumpfhuette). */
final class ScatteredPieces {
    private ScatteredPieces() {
    }

    static final class DesertPyramid extends VirtualPiece {
        DesertPyramid(RandomSource random) {
            super(random, 21, 15, 21);
        }

        @Override
        void build() {
            final Object level = null, chunkBB = null;

      if (true) {
         this.generateBox(
            level, chunkBB, 0, -4, 0, this.width - 1, 0, this.depth - 1, Blocks.SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), false
         );

         for (int pos = 1; pos <= 9; pos++) {
            this.generateBox(
               level,
               chunkBB,
               pos,
               pos,
               pos,
               this.width - 1 - pos,
               pos,
               this.depth - 1 - pos,
               Blocks.SANDSTONE.defaultBlockState(),
               Blocks.SANDSTONE.defaultBlockState(),
               false
            );
            this.generateBox(
               level,
               chunkBB,
               pos + 1,
               pos,
               pos + 1,
               this.width - 2 - pos,
               pos,
               this.depth - 2 - pos,
               Blocks.AIR.defaultBlockState(),
               Blocks.AIR.defaultBlockState(),
               false
            );
         }

         for (int x = 0; x < this.width; x++) {
            for (int z = 0; z < this.depth; z++) {
               int startY = -5;
               this.fillColumnDown(level, Blocks.SANDSTONE.defaultBlockState(), x, -5, z, chunkBB);
            }
         }

         BlockState northStairs = Blocks.SANDSTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
         BlockState southStairs = Blocks.SANDSTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
         BlockState eastStairs = Blocks.SANDSTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
         BlockState westStairs = Blocks.SANDSTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST);
         this.generateBox(level, chunkBB, 0, 0, 0, 4, 9, 4, Blocks.SANDSTONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 1, 10, 1, 3, 10, 3, Blocks.SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), false);
         this.placeBlock(level, northStairs, 2, 10, 0, chunkBB);
         this.placeBlock(level, southStairs, 2, 10, 4, chunkBB);
         this.placeBlock(level, eastStairs, 0, 10, 2, chunkBB);
         this.placeBlock(level, westStairs, 4, 10, 2, chunkBB);
         this.generateBox(
            level, chunkBB, this.width - 5, 0, 0, this.width - 1, 9, 4, Blocks.SANDSTONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), false
         );
         this.generateBox(
            level, chunkBB, this.width - 4, 10, 1, this.width - 2, 10, 3, Blocks.SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), false
         );
         this.placeBlock(level, northStairs, this.width - 3, 10, 0, chunkBB);
         this.placeBlock(level, southStairs, this.width - 3, 10, 4, chunkBB);
         this.placeBlock(level, eastStairs, this.width - 5, 10, 2, chunkBB);
         this.placeBlock(level, westStairs, this.width - 1, 10, 2, chunkBB);
         this.generateBox(level, chunkBB, 8, 0, 0, 12, 4, 4, Blocks.SANDSTONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 9, 1, 0, 11, 3, 4, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
         this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), 9, 1, 1, chunkBB);
         this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), 9, 2, 1, chunkBB);
         this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), 9, 3, 1, chunkBB);
         this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), 10, 3, 1, chunkBB);
         this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), 11, 3, 1, chunkBB);
         this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), 11, 2, 1, chunkBB);
         this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), 11, 1, 1, chunkBB);
         this.generateBox(level, chunkBB, 4, 1, 1, 8, 3, 3, Blocks.SANDSTONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 4, 1, 2, 8, 2, 2, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 12, 1, 1, 16, 3, 3, Blocks.SANDSTONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 12, 1, 2, 16, 2, 2, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
         this.generateBox(
            level, chunkBB, 5, 4, 5, this.width - 6, 4, this.depth - 6, Blocks.SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), false
         );
         this.generateBox(level, chunkBB, 9, 4, 9, 11, 4, 11, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 8, 1, 8, 8, 3, 8, Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 12, 1, 8, 12, 3, 8, Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 8, 1, 12, 8, 3, 12, Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 12, 1, 12, 12, 3, 12, Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 1, 1, 5, 4, 4, 11, Blocks.SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), false);
         this.generateBox(
            level, chunkBB, this.width - 5, 1, 5, this.width - 2, 4, 11, Blocks.SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), false
         );
         this.generateBox(level, chunkBB, 6, 7, 9, 6, 7, 11, Blocks.SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), false);
         this.generateBox(
            level, chunkBB, this.width - 7, 7, 9, this.width - 7, 7, 11, Blocks.SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), false
         );
         this.generateBox(level, chunkBB, 5, 5, 9, 5, 7, 11, Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(), false);
         this.generateBox(
            level,
            chunkBB,
            this.width - 6,
            5,
            9,
            this.width - 6,
            7,
            11,
            Blocks.CUT_SANDSTONE.defaultBlockState(),
            Blocks.CUT_SANDSTONE.defaultBlockState(),
            false
         );
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 5, 5, 10, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 5, 6, 10, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 6, 6, 10, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), this.width - 6, 5, 10, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), this.width - 6, 6, 10, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), this.width - 7, 6, 10, chunkBB);
         this.generateBox(level, chunkBB, 2, 4, 4, 2, 6, 4, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
         this.generateBox(level, chunkBB, this.width - 3, 4, 4, this.width - 3, 6, 4, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
         this.placeBlock(level, northStairs, 2, 4, 5, chunkBB);
         this.placeBlock(level, northStairs, 2, 3, 4, chunkBB);
         this.placeBlock(level, northStairs, this.width - 3, 4, 5, chunkBB);
         this.placeBlock(level, northStairs, this.width - 3, 3, 4, chunkBB);
         this.generateBox(level, chunkBB, 1, 1, 3, 2, 2, 3, Blocks.SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), false);
         this.generateBox(
            level, chunkBB, this.width - 3, 1, 3, this.width - 2, 2, 3, Blocks.SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), false
         );
         this.placeBlock(level, Blocks.SANDSTONE.defaultBlockState(), 1, 1, 2, chunkBB);
         this.placeBlock(level, Blocks.SANDSTONE.defaultBlockState(), this.width - 2, 1, 2, chunkBB);
         this.placeBlock(level, Blocks.SANDSTONE_SLAB.defaultBlockState(), 1, 2, 2, chunkBB);
         this.placeBlock(level, Blocks.SANDSTONE_SLAB.defaultBlockState(), this.width - 2, 2, 2, chunkBB);
         this.placeBlock(level, westStairs, 2, 1, 2, chunkBB);
         this.placeBlock(level, eastStairs, this.width - 3, 1, 2, chunkBB);
         this.generateBox(level, chunkBB, 4, 3, 5, 4, 3, 17, Blocks.SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), false);
         this.generateBox(
            level, chunkBB, this.width - 5, 3, 5, this.width - 5, 3, 17, Blocks.SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), false
         );
         this.generateBox(level, chunkBB, 3, 1, 5, 4, 2, 16, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
         this.generateBox(level, chunkBB, this.width - 6, 1, 5, this.width - 5, 2, 16, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);

         for (int z = 5; z <= 17; z += 2) {
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), 4, 1, z, chunkBB);
            this.placeBlock(level, Blocks.CHISELED_SANDSTONE.defaultBlockState(), 4, 2, z, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), this.width - 5, 1, z, chunkBB);
            this.placeBlock(level, Blocks.CHISELED_SANDSTONE.defaultBlockState(), this.width - 5, 2, z, chunkBB);
         }

         this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), 10, 0, 7, chunkBB);
         this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), 10, 0, 8, chunkBB);
         this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), 9, 0, 9, chunkBB);
         this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), 11, 0, 9, chunkBB);
         this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), 8, 0, 10, chunkBB);
         this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), 12, 0, 10, chunkBB);
         this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), 7, 0, 10, chunkBB);
         this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), 13, 0, 10, chunkBB);
         this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), 9, 0, 11, chunkBB);
         this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), 11, 0, 11, chunkBB);
         this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), 10, 0, 12, chunkBB);
         this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), 10, 0, 13, chunkBB);
         this.placeBlock(level, Blocks.DYED_TERRACOTTA.blue().defaultBlockState(), 10, 0, 10, chunkBB);

         for (int x = 0; x <= this.width - 1; x += this.width - 1) {
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x, 2, 1, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x, 2, 2, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x, 2, 3, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x, 3, 1, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x, 3, 2, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x, 3, 3, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x, 4, 1, chunkBB);
            this.placeBlock(level, Blocks.CHISELED_SANDSTONE.defaultBlockState(), x, 4, 2, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x, 4, 3, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x, 5, 1, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x, 5, 2, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x, 5, 3, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x, 6, 1, chunkBB);
            this.placeBlock(level, Blocks.CHISELED_SANDSTONE.defaultBlockState(), x, 6, 2, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x, 6, 3, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x, 7, 1, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x, 7, 2, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x, 7, 3, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x, 8, 1, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x, 8, 2, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x, 8, 3, chunkBB);
         }

         for (int x = 2; x <= this.width - 3; x += this.width - 3 - 2) {
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x - 1, 2, 0, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x, 2, 0, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x + 1, 2, 0, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x - 1, 3, 0, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x, 3, 0, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x + 1, 3, 0, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x - 1, 4, 0, chunkBB);
            this.placeBlock(level, Blocks.CHISELED_SANDSTONE.defaultBlockState(), x, 4, 0, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x + 1, 4, 0, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x - 1, 5, 0, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x, 5, 0, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x + 1, 5, 0, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x - 1, 6, 0, chunkBB);
            this.placeBlock(level, Blocks.CHISELED_SANDSTONE.defaultBlockState(), x, 6, 0, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x + 1, 6, 0, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x - 1, 7, 0, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x, 7, 0, chunkBB);
            this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), x + 1, 7, 0, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x - 1, 8, 0, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x, 8, 0, chunkBB);
            this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), x + 1, 8, 0, chunkBB);
         }

         this.generateBox(level, chunkBB, 8, 4, 0, 12, 6, 0, Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(), false);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 8, 6, 0, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 12, 6, 0, chunkBB);
         this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), 9, 5, 0, chunkBB);
         this.placeBlock(level, Blocks.CHISELED_SANDSTONE.defaultBlockState(), 10, 5, 0, chunkBB);
         this.placeBlock(level, Blocks.DYED_TERRACOTTA.orange().defaultBlockState(), 11, 5, 0, chunkBB);
         this.generateBox(level, chunkBB, 8, -14, 8, 12, -11, 12, Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(), false);
         this.generateBox(
            level, chunkBB, 8, -10, 8, 12, -10, 12, Blocks.CHISELED_SANDSTONE.defaultBlockState(), Blocks.CHISELED_SANDSTONE.defaultBlockState(), false
         );
         this.generateBox(level, chunkBB, 8, -9, 8, 12, -9, 12, Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 8, -8, 8, 12, -1, 12, Blocks.SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 9, -11, 9, 11, -1, 11, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
         this.placeBlock(level, Blocks.STONE_PRESSURE_PLATE.defaultBlockState(), 10, -11, 10, chunkBB);
         this.generateBox(level, chunkBB, 9, -13, 9, 11, -13, 11, Blocks.TNT.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 8, -11, 10, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 8, -10, 10, chunkBB);
         this.placeBlock(level, Blocks.CHISELED_SANDSTONE.defaultBlockState(), 7, -10, 10, chunkBB);
         this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), 7, -11, 10, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 12, -11, 10, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 12, -10, 10, chunkBB);
         this.placeBlock(level, Blocks.CHISELED_SANDSTONE.defaultBlockState(), 13, -10, 10, chunkBB);
         this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), 13, -11, 10, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 10, -11, 8, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 10, -10, 8, chunkBB);
         this.placeBlock(level, Blocks.CHISELED_SANDSTONE.defaultBlockState(), 10, -10, 7, chunkBB);
         this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), 10, -11, 7, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 10, -11, 12, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 10, -10, 12, chunkBB);
         this.placeBlock(level, Blocks.CHISELED_SANDSTONE.defaultBlockState(), 10, -10, 13, chunkBB);
         this.placeBlock(level, Blocks.CUT_SANDSTONE.defaultBlockState(), 10, -11, 13, chunkBB);

         for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!this.hasPlacedChest[direction.get2DDataValue()]) {
               int xo = direction.getStepX() * 2;
               int zo = direction.getStepZ() * 2;
               this.hasPlacedChest[direction.get2DDataValue()] = this.createChest(
                  level, chunkBB, random, 10 + xo, -11, 10 + zo, null
               );
            }
         }

         this.addCellar(level, chunkBB);
      }
           }

        private void addCellar(final Object level, final Object chunkBB) {
           BlockPos roomCenter = new BlockPos(16, -4, 13);
           this.addCellarStairs(roomCenter, level, chunkBB);
           this.addCellarRoom(roomCenter, level, chunkBB);
        }

        private void addCellarStairs(final BlockPos roomCenter, final Object level, final Object chunkBB) {
           int x = roomCenter.getX();
           int y = roomCenter.getY();
           int z = roomCenter.getZ();
           BlockState sandStoneStairs = Blocks.SANDSTONE_STAIRS.defaultBlockState();
           this.placeBlock(level, sandStoneStairs.rotate(Rotation.COUNTERCLOCKWISE_90), 13, -1, 17, chunkBB);
           this.placeBlock(level, sandStoneStairs.rotate(Rotation.COUNTERCLOCKWISE_90), 14, -2, 17, chunkBB);
           this.placeBlock(level, sandStoneStairs.rotate(Rotation.COUNTERCLOCKWISE_90), 15, -3, 17, chunkBB);
           BlockState sand = Blocks.SAND.defaultBlockState();
           BlockState sandStone = Blocks.SANDSTONE.defaultBlockState();
           boolean variant = random.nextBoolean();
           this.placeBlock(level, sand, x - 4, y + 4, z + 4, chunkBB);
           this.placeBlock(level, sand, x - 3, y + 4, z + 4, chunkBB);
           this.placeBlock(level, sand, x - 2, y + 4, z + 4, chunkBB);
           this.placeBlock(level, sand, x - 1, y + 4, z + 4, chunkBB);
           this.placeBlock(level, sand, x, y + 4, z + 4, chunkBB);
           this.placeBlock(level, sand, x - 2, y + 3, z + 4, chunkBB);
           this.placeBlock(level, variant ? sand : sandStone, x - 1, y + 3, z + 4, chunkBB);
           this.placeBlock(level, !variant ? sand : sandStone, x, y + 3, z + 4, chunkBB);
           this.placeBlock(level, sand, x - 1, y + 2, z + 4, chunkBB);
           this.placeBlock(level, sandStone, x, y + 2, z + 4, chunkBB);
           this.placeBlock(level, sand, x, y + 1, z + 4, chunkBB);
        }

        private void addCellarRoom(final BlockPos roomCenter, final Object level, final Object chunkBB) {
           int x = roomCenter.getX();
           int y = roomCenter.getY();
           int z = roomCenter.getZ();
           BlockState cutSandStone = Blocks.CUT_SANDSTONE.defaultBlockState();
           BlockState hieroglyphsSandStone = Blocks.CHISELED_SANDSTONE.defaultBlockState();
           this.generateBox(level, chunkBB, x - 3, y + 1, z - 3, x - 3, y + 1, z + 2, cutSandStone, cutSandStone, true);
           this.generateBox(level, chunkBB, x + 3, y + 1, z - 3, x + 3, y + 1, z + 2, cutSandStone, cutSandStone, true);
           this.generateBox(level, chunkBB, x - 3, y + 1, z - 3, x + 3, y + 1, z - 2, cutSandStone, cutSandStone, true);
           this.generateBox(level, chunkBB, x - 3, y + 1, z + 3, x + 3, y + 1, z + 3, cutSandStone, cutSandStone, true);
           this.generateBox(level, chunkBB, x - 3, y + 2, z - 3, x - 3, y + 2, z + 2, hieroglyphsSandStone, hieroglyphsSandStone, true);
           this.generateBox(level, chunkBB, x + 3, y + 2, z - 3, x + 3, y + 2, z + 2, hieroglyphsSandStone, hieroglyphsSandStone, true);
           this.generateBox(level, chunkBB, x - 3, y + 2, z - 3, x + 3, y + 2, z - 2, hieroglyphsSandStone, hieroglyphsSandStone, true);
           this.generateBox(level, chunkBB, x - 3, y + 2, z + 3, x + 3, y + 2, z + 3, hieroglyphsSandStone, hieroglyphsSandStone, true);
           this.generateBox(level, chunkBB, x - 3, -1, z - 3, x - 3, -1, z + 2, cutSandStone, cutSandStone, true);
           this.generateBox(level, chunkBB, x + 3, -1, z - 3, x + 3, -1, z + 2, cutSandStone, cutSandStone, true);
           this.generateBox(level, chunkBB, x - 3, -1, z - 3, x + 3, -1, z - 2, cutSandStone, cutSandStone, true);
           this.generateBox(level, chunkBB, x - 3, -1, z + 3, x + 3, -1, z + 3, cutSandStone, cutSandStone, true);
           this.placeSandBox(x - 2, y + 1, z - 2, x + 2, y + 3, z + 2);
           this.placeCollapsedRoof(level, chunkBB, x - 2, y + 4, z - 2, x + 2, z + 2);
           BlockState orangeTeracotta = Blocks.DYED_TERRACOTTA.orange().defaultBlockState();
           BlockState blueTeracotta = Blocks.DYED_TERRACOTTA.blue().defaultBlockState();
           this.placeBlock(level, blueTeracotta, x, y, z, chunkBB);
           this.placeBlock(level, orangeTeracotta, x + 1, y, z - 1, chunkBB);
           this.placeBlock(level, orangeTeracotta, x + 1, y, z + 1, chunkBB);
           this.placeBlock(level, orangeTeracotta, x - 1, y, z - 1, chunkBB);
           this.placeBlock(level, orangeTeracotta, x - 1, y, z + 1, chunkBB);
           this.placeBlock(level, orangeTeracotta, x + 2, y, z, chunkBB);
           this.placeBlock(level, orangeTeracotta, x - 2, y, z, chunkBB);
           this.placeBlock(level, orangeTeracotta, x, y, z + 2, chunkBB);
           this.placeBlock(level, orangeTeracotta, x, y, z - 2, chunkBB);
           this.placeBlock(level, orangeTeracotta, x + 3, y, z, chunkBB);
           this.placeSand(x + 3, y + 1, z);
           this.placeSand(x + 3, y + 2, z);
           this.placeBlock(level, cutSandStone, x + 4, y + 1, z, chunkBB);
           this.placeBlock(level, hieroglyphsSandStone, x + 4, y + 2, z, chunkBB);
           this.placeBlock(level, orangeTeracotta, x - 3, y, z, chunkBB);
           this.placeSand(x - 3, y + 1, z);
           this.placeSand(x - 3, y + 2, z);
           this.placeBlock(level, cutSandStone, x - 4, y + 1, z, chunkBB);
           this.placeBlock(level, hieroglyphsSandStone, x - 4, y + 2, z, chunkBB);
           this.placeBlock(level, orangeTeracotta, x, y, z + 3, chunkBB);
           this.placeSand(x, y + 1, z + 3);
           this.placeSand(x, y + 2, z + 3);
           this.placeBlock(level, orangeTeracotta, x, y, z - 3, chunkBB);
           this.placeSand(x, y + 1, z - 3);
           this.placeSand(x, y + 2, z - 3);
           this.placeBlock(level, cutSandStone, x, y + 1, z - 4, chunkBB);
           this.placeBlock(level, hieroglyphsSandStone, x, -2, z - 4, chunkBB);
        }

        private void placeSand(final int x, final int y, final int z) {
           BlockPos worldPos = this.getWorldPos(x, y, z);
           this.potentialSuspiciousSandWorldPositions.add(worldPos);
        }

        private void placeSandBox(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
           for (int y = y0; y <= y1; y++) {
              for (int x = x0; x <= x1; x++) {
                 for (int z = z0; z <= z1; z++) {
                    this.placeSand(x, y, z);
                 }
              }
           }
        }

        private void placeCollapsedRoofPiece(final Object level, final int x, final int y, final int z, final Object chunkBB) {
           if (random.nextFloat() < 0.33F) {
              BlockState blockState = Blocks.SANDSTONE.defaultBlockState();
              this.placeBlock(level, blockState, x, y, z, chunkBB);
           } else {
              BlockState blockState = Blocks.SAND.defaultBlockState();
              this.placeBlock(level, blockState, x, y, z, chunkBB);
           }
        }

        private void placeCollapsedRoof(final Object level, final Object chunkBB, final int x0, final int y0, final int z0, final int x1, final int z1) {
           for (int x = x0; x <= x1; x++) {
              for (int z = z0; z <= z1; z++) {
                 this.placeCollapsedRoofPiece(level, x, y0, z, chunkBB);
              }
           }

           
           int roofPosX = random.nextIntBetweenInclusive(x0, x1);
           int roofPosZ = random.nextIntBetweenInclusive(z0, z1);
           this.randomCollapsedRoofPos = new BlockPos(this.getWorldX(roofPosX, roofPosZ), this.getWorldY(y0), this.getWorldZ(roofPosX, roofPosZ));
        }
    }

    static final class JungleTemple extends VirtualPiece {
        JungleTemple(RandomSource random) {
            super(random, 12, 10, 15);
        }

        @Override
        void build() {
            final Object level = null, chunkBB = null;

      if (true) {
         this.generateBox(level, chunkBB, 0, -4, 0, this.width - 1, 0, this.depth - 1, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 2, 1, 2, 9, 2, 2, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 2, 1, 12, 9, 2, 12, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 2, 1, 3, 2, 2, 11, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 9, 1, 3, 9, 2, 11, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 1, 3, 1, 10, 6, 1, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 1, 3, 13, 10, 6, 13, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 1, 3, 2, 1, 6, 12, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 10, 3, 2, 10, 6, 12, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 2, 3, 2, 9, 3, 12, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 2, 6, 2, 9, 6, 12, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 3, 7, 3, 8, 7, 11, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 4, 8, 4, 7, 8, 10, false, random, STONE_SELECTOR);
         this.generateAirBox(level, chunkBB, 3, 1, 3, 8, 2, 11);
         this.generateAirBox(level, chunkBB, 4, 3, 6, 7, 3, 9);
         this.generateAirBox(level, chunkBB, 2, 4, 2, 9, 5, 12);
         this.generateAirBox(level, chunkBB, 4, 6, 5, 7, 6, 9);
         this.generateAirBox(level, chunkBB, 5, 7, 6, 6, 7, 8);
         this.generateAirBox(level, chunkBB, 5, 1, 2, 6, 2, 2);
         this.generateAirBox(level, chunkBB, 5, 2, 12, 6, 2, 12);
         this.generateAirBox(level, chunkBB, 5, 5, 1, 6, 5, 1);
         this.generateAirBox(level, chunkBB, 5, 5, 13, 6, 5, 13);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 1, 5, 5, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 10, 5, 5, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 1, 5, 9, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 10, 5, 9, chunkBB);

         for (int z = 0; z <= 14; z += 14) {
            this.generateBox(level, chunkBB, 2, 4, z, 2, 5, z, false, random, STONE_SELECTOR);
            this.generateBox(level, chunkBB, 4, 4, z, 4, 5, z, false, random, STONE_SELECTOR);
            this.generateBox(level, chunkBB, 7, 4, z, 7, 5, z, false, random, STONE_SELECTOR);
            this.generateBox(level, chunkBB, 9, 4, z, 9, 5, z, false, random, STONE_SELECTOR);
         }

         this.generateBox(level, chunkBB, 5, 6, 0, 6, 6, 0, false, random, STONE_SELECTOR);

         for (int x = 0; x <= 11; x += 11) {
            for (int z = 2; z <= 12; z += 2) {
               this.generateBox(level, chunkBB, x, 4, z, x, 5, z, false, random, STONE_SELECTOR);
            }

            this.generateBox(level, chunkBB, x, 6, 5, x, 6, 5, false, random, STONE_SELECTOR);
            this.generateBox(level, chunkBB, x, 6, 9, x, 6, 9, false, random, STONE_SELECTOR);
         }

         this.generateBox(level, chunkBB, 2, 7, 2, 2, 9, 2, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 9, 7, 2, 9, 9, 2, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 2, 7, 12, 2, 9, 12, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 9, 7, 12, 9, 9, 12, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 4, 9, 4, 4, 9, 4, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 7, 9, 4, 7, 9, 4, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 4, 9, 10, 4, 9, 10, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 7, 9, 10, 7, 9, 10, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 5, 9, 7, 6, 9, 7, false, random, STONE_SELECTOR);
         BlockState eastStairs = Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
         BlockState westStairs = Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST);
         BlockState southStairs = Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
         BlockState northStairs = Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
         this.placeBlock(level, northStairs, 5, 9, 6, chunkBB);
         this.placeBlock(level, northStairs, 6, 9, 6, chunkBB);
         this.placeBlock(level, southStairs, 5, 9, 8, chunkBB);
         this.placeBlock(level, southStairs, 6, 9, 8, chunkBB);
         this.placeBlock(level, northStairs, 4, 0, 0, chunkBB);
         this.placeBlock(level, northStairs, 5, 0, 0, chunkBB);
         this.placeBlock(level, northStairs, 6, 0, 0, chunkBB);
         this.placeBlock(level, northStairs, 7, 0, 0, chunkBB);
         this.placeBlock(level, northStairs, 4, 1, 8, chunkBB);
         this.placeBlock(level, northStairs, 4, 2, 9, chunkBB);
         this.placeBlock(level, northStairs, 4, 3, 10, chunkBB);
         this.placeBlock(level, northStairs, 7, 1, 8, chunkBB);
         this.placeBlock(level, northStairs, 7, 2, 9, chunkBB);
         this.placeBlock(level, northStairs, 7, 3, 10, chunkBB);
         this.generateBox(level, chunkBB, 4, 1, 9, 4, 1, 9, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 7, 1, 9, 7, 1, 9, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 4, 1, 10, 7, 2, 10, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 5, 4, 5, 6, 4, 5, false, random, STONE_SELECTOR);
         this.placeBlock(level, eastStairs, 4, 4, 5, chunkBB);
         this.placeBlock(level, westStairs, 7, 4, 5, chunkBB);

         for (int i = 0; i < 4; i++) {
            this.placeBlock(level, southStairs, 5, 0 - i, 6 + i, chunkBB);
            this.placeBlock(level, southStairs, 6, 0 - i, 6 + i, chunkBB);
            this.generateAirBox(level, chunkBB, 5, 0 - i, 7 + i, 6, 0 - i, 9 + i);
         }

         this.generateAirBox(level, chunkBB, 1, -3, 12, 10, -1, 13);
         this.generateAirBox(level, chunkBB, 1, -3, 1, 3, -1, 13);
         this.generateAirBox(level, chunkBB, 1, -3, 1, 9, -1, 5);

         for (int z = 1; z <= 13; z += 2) {
            this.generateBox(level, chunkBB, 1, -3, z, 1, -2, z, false, random, STONE_SELECTOR);
         }

         for (int z = 2; z <= 12; z += 2) {
            this.generateBox(level, chunkBB, 1, -1, z, 3, -1, z, false, random, STONE_SELECTOR);
         }

         this.generateBox(level, chunkBB, 2, -2, 1, 5, -2, 1, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 7, -2, 1, 9, -2, 1, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 6, -3, 1, 6, -3, 1, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 6, -1, 1, 6, -1, 1, false, random, STONE_SELECTOR);
         this.placeBlock(
            level,
            Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, Direction.EAST).setValue(TripWireHookBlock.ATTACHED, true),
            1,
            -3,
            8,
            chunkBB
         );
         this.placeBlock(
            level,
            Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, Direction.WEST).setValue(TripWireHookBlock.ATTACHED, true),
            4,
            -3,
            8,
            chunkBB
         );
         this.placeBlock(
            level,
            Blocks.TRIPWIRE.defaultBlockState().setValue(TripWireBlock.EAST, true).setValue(TripWireBlock.WEST, true).setValue(TripWireBlock.ATTACHED, true),
            2,
            -3,
            8,
            chunkBB
         );
         this.placeBlock(
            level,
            Blocks.TRIPWIRE.defaultBlockState().setValue(TripWireBlock.EAST, true).setValue(TripWireBlock.WEST, true).setValue(TripWireBlock.ATTACHED, true),
            3,
            -3,
            8,
            chunkBB
         );
         BlockState redstoneWireNS = Blocks.REDSTONE_WIRE
            .defaultBlockState()
            .setValue(RedstoneWireBlock.NORTH, RedstoneSide.SIDE)
            .setValue(RedstoneWireBlock.SOUTH, RedstoneSide.SIDE);
         this.placeBlock(level, redstoneWireNS, 5, -3, 7, chunkBB);
         this.placeBlock(level, redstoneWireNS, 5, -3, 6, chunkBB);
         this.placeBlock(level, redstoneWireNS, 5, -3, 5, chunkBB);
         this.placeBlock(level, redstoneWireNS, 5, -3, 4, chunkBB);
         this.placeBlock(level, redstoneWireNS, 5, -3, 3, chunkBB);
         this.placeBlock(level, redstoneWireNS, 5, -3, 2, chunkBB);
         this.placeBlock(
            level,
            Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedstoneWireBlock.NORTH, RedstoneSide.SIDE).setValue(RedstoneWireBlock.WEST, RedstoneSide.SIDE),
            5,
            -3,
            1,
            chunkBB
         );
         this.placeBlock(
            level,
            Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedstoneWireBlock.EAST, RedstoneSide.SIDE).setValue(RedstoneWireBlock.WEST, RedstoneSide.SIDE),
            4,
            -3,
            1,
            chunkBB
         );
         this.placeBlock(level, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3, -3, 1, chunkBB);
         if (!this.placedTrap1) {
            this.placedTrap1 = this.createDispenser(level, chunkBB, random, 3, -2, 1, Direction.NORTH, null);
         }

         this.placeBlock(level, Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true), 3, -2, 2, chunkBB);
         this.placeBlock(
            level,
            Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, Direction.NORTH).setValue(TripWireHookBlock.ATTACHED, true),
            7,
            -3,
            1,
            chunkBB
         );
         this.placeBlock(
            level,
            Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, Direction.SOUTH).setValue(TripWireHookBlock.ATTACHED, true),
            7,
            -3,
            5,
            chunkBB
         );
         this.placeBlock(
            level,
            Blocks.TRIPWIRE.defaultBlockState().setValue(TripWireBlock.NORTH, true).setValue(TripWireBlock.SOUTH, true).setValue(TripWireBlock.ATTACHED, true),
            7,
            -3,
            2,
            chunkBB
         );
         this.placeBlock(
            level,
            Blocks.TRIPWIRE.defaultBlockState().setValue(TripWireBlock.NORTH, true).setValue(TripWireBlock.SOUTH, true).setValue(TripWireBlock.ATTACHED, true),
            7,
            -3,
            3,
            chunkBB
         );
         this.placeBlock(
            level,
            Blocks.TRIPWIRE.defaultBlockState().setValue(TripWireBlock.NORTH, true).setValue(TripWireBlock.SOUTH, true).setValue(TripWireBlock.ATTACHED, true),
            7,
            -3,
            4,
            chunkBB
         );
         this.placeBlock(
            level,
            Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedstoneWireBlock.EAST, RedstoneSide.SIDE).setValue(RedstoneWireBlock.WEST, RedstoneSide.SIDE),
            8,
            -3,
            6,
            chunkBB
         );
         this.placeBlock(
            level,
            Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedstoneWireBlock.WEST, RedstoneSide.SIDE).setValue(RedstoneWireBlock.SOUTH, RedstoneSide.SIDE),
            9,
            -3,
            6,
            chunkBB
         );
         this.placeBlock(
            level,
            Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedstoneWireBlock.NORTH, RedstoneSide.SIDE).setValue(RedstoneWireBlock.SOUTH, RedstoneSide.UP),
            9,
            -3,
            5,
            chunkBB
         );
         this.placeBlock(level, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 9, -3, 4, chunkBB);
         this.placeBlock(level, redstoneWireNS, 9, -2, 4, chunkBB);
         if (!this.placedTrap2) {
            this.placedTrap2 = this.createDispenser(level, chunkBB, random, 9, -2, 3, Direction.WEST, null);
         }

         this.placeBlock(level, Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true), 8, -1, 3, chunkBB);
         this.placeBlock(level, Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true), 8, -2, 3, chunkBB);
         if (!this.placedMainChest) {
            this.placedMainChest = this.createChest(level, chunkBB, random, 8, -3, 3, null);
         }

         this.placeBlock(level, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 9, -3, 2, chunkBB);
         this.placeBlock(level, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 8, -3, 1, chunkBB);
         this.placeBlock(level, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 4, -3, 5, chunkBB);
         this.placeBlock(level, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 5, -2, 5, chunkBB);
         this.placeBlock(level, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 5, -1, 5, chunkBB);
         this.placeBlock(level, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 6, -3, 5, chunkBB);
         this.placeBlock(level, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 7, -2, 5, chunkBB);
         this.placeBlock(level, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 7, -1, 5, chunkBB);
         this.placeBlock(level, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 8, -3, 5, chunkBB);
         this.generateBox(level, chunkBB, 9, -1, 1, 9, -1, 5, false, random, STONE_SELECTOR);
         this.generateAirBox(level, chunkBB, 8, -3, 8, 10, -1, 10);
         this.placeBlock(level, Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 8, -2, 11, chunkBB);
         this.placeBlock(level, Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 9, -2, 11, chunkBB);
         this.placeBlock(level, Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 10, -2, 11, chunkBB);
         BlockState lever = Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACING, Direction.NORTH).setValue(LeverBlock.FACE, AttachFace.WALL);
         this.placeBlock(level, lever, 8, -2, 12, chunkBB);
         this.placeBlock(level, lever, 9, -2, 12, chunkBB);
         this.placeBlock(level, lever, 10, -2, 12, chunkBB);
         this.generateBox(level, chunkBB, 8, -3, 8, 8, -3, 10, false, random, STONE_SELECTOR);
         this.generateBox(level, chunkBB, 10, -3, 8, 10, -3, 10, false, random, STONE_SELECTOR);
         this.placeBlock(level, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 10, -2, 9, chunkBB);
         this.placeBlock(level, redstoneWireNS, 8, -2, 9, chunkBB);
         this.placeBlock(level, redstoneWireNS, 8, -2, 10, chunkBB);
         this.placeBlock(
            level,
            Blocks.REDSTONE_WIRE
               .defaultBlockState()
               .setValue(RedstoneWireBlock.NORTH, RedstoneSide.SIDE)
               .setValue(RedstoneWireBlock.SOUTH, RedstoneSide.SIDE)
               .setValue(RedstoneWireBlock.EAST, RedstoneSide.SIDE)
               .setValue(RedstoneWireBlock.WEST, RedstoneSide.SIDE),
            10,
            -1,
            9,
            chunkBB
         );
         this.placeBlock(level, Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP), 9, -2, 8, chunkBB);
         this.placeBlock(level, Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.WEST), 10, -2, 8, chunkBB);
         this.placeBlock(level, Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.WEST), 10, -1, 8, chunkBB);
         this.placeBlock(level, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.NORTH), 10, -2, 10, chunkBB);
         if (!this.placedHiddenChest) {
            this.placedHiddenChest = this.createChest(level, chunkBB, random, 9, -3, 10, null);
         }
      }
           }

        private static final MossStoneSelector STONE_SELECTOR = new MossStoneSelector();

        private static class MossStoneSelector extends BlockSelector {
            @Override
            void next(RandomSource random, int worldX, int worldY, int worldZ, boolean isEdge) {
                if (random.nextFloat() < 0.4F) this.next = Blocks.COBBLESTONE.defaultBlockState();
                else this.next = Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            }
        }
    }

    static final class SwampHut extends VirtualPiece {
        SwampHut(RandomSource random) {
            super(random, 7, 7, 9);
        }

        @Override
        void build() {
            final Object level = null, chunkBB = null;

      if (true) {
         this.generateBox(level, chunkBB, 1, 1, 1, 5, 1, 7, Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 1, 4, 2, 5, 4, 7, Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 2, 1, 0, 4, 1, 0, Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 2, 2, 2, 3, 3, 2, Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 1, 2, 3, 1, 3, 6, Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 5, 2, 3, 5, 3, 6, Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 2, 2, 7, 4, 3, 7, Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 1, 0, 2, 1, 3, 2, Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 5, 0, 2, 5, 3, 2, Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 1, 0, 7, 1, 3, 7, Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), false);
         this.generateBox(level, chunkBB, 5, 0, 7, 5, 3, 7, Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), false);
         this.placeBlock(level, Blocks.OAK_FENCE.defaultBlockState(), 2, 3, 2, chunkBB);
         this.placeBlock(level, Blocks.OAK_FENCE.defaultBlockState(), 3, 3, 7, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 1, 3, 4, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 5, 3, 4, chunkBB);
         this.placeBlock(level, Blocks.AIR.defaultBlockState(), 5, 3, 5, chunkBB);
         this.placeBlock(level, Blocks.POTTED_RED_MUSHROOM.defaultBlockState(), 1, 3, 5, chunkBB);
         this.placeBlock(level, Blocks.CRAFTING_TABLE.defaultBlockState(), 3, 2, 6, chunkBB);
         this.placeBlock(level, Blocks.CAULDRON.defaultBlockState(), 4, 2, 6, chunkBB);
         this.placeBlock(level, Blocks.OAK_FENCE.defaultBlockState(), 1, 2, 1, chunkBB);
         this.placeBlock(level, Blocks.OAK_FENCE.defaultBlockState(), 5, 2, 1, chunkBB);
         BlockState northStairs = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
         BlockState eastStairs = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
         BlockState westStairs = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST);
         BlockState southStairs = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
         this.generateBox(level, chunkBB, 0, 4, 1, 6, 4, 1, northStairs, northStairs, false);
         this.generateBox(level, chunkBB, 0, 4, 2, 0, 4, 7, eastStairs, eastStairs, false);
         this.generateBox(level, chunkBB, 6, 4, 2, 6, 4, 7, westStairs, westStairs, false);
         this.generateBox(level, chunkBB, 0, 4, 8, 6, 4, 8, southStairs, southStairs, false);
         this.placeBlock(level, northStairs.setValue(StairBlock.SHAPE, StairsShape.OUTER_RIGHT), 0, 4, 1, chunkBB);
         this.placeBlock(level, northStairs.setValue(StairBlock.SHAPE, StairsShape.OUTER_LEFT), 6, 4, 1, chunkBB);
         this.placeBlock(level, southStairs.setValue(StairBlock.SHAPE, StairsShape.OUTER_LEFT), 0, 4, 8, chunkBB);
         this.placeBlock(level, southStairs.setValue(StairBlock.SHAPE, StairsShape.OUTER_RIGHT), 6, 4, 8, chunkBB);

         for (int z = 2; z <= 7; z += 5) {
            for (int x = 1; x <= 5; x += 4) {
               this.fillColumnDown(level, Blocks.OAK_LOG.defaultBlockState(), x, -1, z, chunkBB);
            }
         }

      }
           }
    }

}

