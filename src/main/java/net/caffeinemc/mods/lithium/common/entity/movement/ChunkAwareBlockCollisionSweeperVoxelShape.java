package net.caffeinemc.mods.lithium.common.entity.movement;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * ChunkAwareBlockCollisionSweeperVoxelShape iterates over blocks in one chunk section at a time. Together with the chunk
 * section keeping track of the amount of oversized blocks inside the number of iterations can often be reduced.
 * <p>
 * Since VoxelShape collisions are not fully associative, this collision sweeper ensures the collision at the
 * greatest position (lexicographically by z, then y, then x) is returned last.
 */
public class ChunkAwareBlockCollisionSweeperVoxelShape extends ChunkAwareBlockCollisionSweeper<VoxelShape> {

    private final boolean hideLastCollision;
    private int maxHitX;
    private int maxHitY;
    private int maxHitZ;
    private VoxelShape maxShape;

    public ChunkAwareBlockCollisionSweeperVoxelShape(Level world, @Nullable Entity entity, AABB box) {
        this(world, entity, box, false);
    }
    public ChunkAwareBlockCollisionSweeperVoxelShape(Level world, @Nullable Entity entity, AABB box, boolean hideLastCollision) {
        super(world, entity, box, hideLastCollision);

        this.maxHitX = Integer.MIN_VALUE;
        this.maxHitY = Integer.MIN_VALUE;
        this.maxHitZ = Integer.MIN_VALUE;
        this.maxShape = null;
        this.hideLastCollision = hideLastCollision;
    }

    public VoxelShape getLastCollision() {
        return this.maxShape;
    }

    /**
     * Advances the sweep forward until finding a block with a box-colliding {@link VoxelShape}.
     *
     * @return the next collided {@link VoxelShape}, or {@link #endOfData()} when no collisions are left
     */
    @Override
    public VoxelShape computeNext() {
        while (true) {
            if (this.cIterated >= this.cTotalSize) {
                if (!this.nextSection()) {
                    break;
                }
            }

            this.cIterated++;


            final int x = this.cX;
            final int y = this.cY;
            final int z = this.cZ;

            //The iteration order within a chunk section is chosen so that it causes a mostly linear array access in the storage.
            //In net.minecraft.world.chunk.PalettedContainer.toIndex x gets the 4 least significant bits, z the 4 above, and y the 4 even higher ones.
            //Linearly accessing arrays is faster than other access patterns.
            if (this.cX < this.cEndX) {
                this.cX++;
            } else if (this.cZ < this.cEndZ) {
                this.cX = this.cStartX;
                this.cZ++;
            } else {
                this.cX = this.cStartX;
                this.cZ = this.cStartZ;
                this.cY++;
                //stop condition was already checked using this.cIterated at the start of the method
            }

            //using < minX and > maxX instead of <= and >= in vanilla, because minX, maxX are the coordinates
            //of the box that wasn't extended for oversized blocks yet.
            final int edgesHit = this.sectionOversizedBlocks ?
                    (x < this.minX || x > this.maxX ? 1 : 0) +
                            (y < this.minY || y > this.maxY ? 1 : 0) +
                            (z < this.minZ || z > this.maxZ ? 1 : 0) : 0;

            if (edgesHit == 3) {
                continue;
            }

            final BlockState state = this.cachedChunkSection.getBlockState(x & 15, y & 15, z & 15);

            if (!canInteractWithBlock(state, edgesHit)) {
                continue;
            }

            this.pos.set(x, y, z);

            VoxelShape collisionShape = this.context.getCollisionShape(state, this.world, this.pos);

            //noinspection ConstantValue
            if (collisionShape != null && collisionShape != Shapes.empty() /* collisionShape should never be null, but we received crash reports. */) {
                VoxelShape collidedShape = getCollidedShape(this.box, this.shape, collisionShape, x, y, z);
                if (collidedShape != null) {
                    if (z >= this.maxHitZ && (z > this.maxHitZ || y >= this.maxHitY && (y > this.maxHitY || x > this.maxHitX))) {
                        this.maxHitX = x;
                        this.maxHitY = y;
                        this.maxHitZ = z;
                        //Always make sure the shape at the maximum position is the last one returned, because
                        // the last shape has a different 1e-7 behavior (no next shape that clips movement to 0).
                        // This does affect certain contraptions: https://github.com/CaffeineMC/lithium-fabric/issues/443
                        VoxelShape previousMaxShape = this.maxShape;
                        this.maxShape = collidedShape;
                        if (previousMaxShape != null) {
                            return previousMaxShape;
                        }
                    } else {
                        return collidedShape;
                    }
                }
            }
        }

        if (!this.hideLastCollision && this.maxShape != null) {
            VoxelShape previousMaxShape = this.maxShape;
            this.maxShape = null;
            return previousMaxShape;
        }

        return this.endOfData();
    }

    public List<VoxelShape> collectAll() {
        ArrayList<VoxelShape> collisions = new ArrayList<>();

        while (this.hasNext()) {
            collisions.add(this.next());
        }

        return collisions;
    }
}
