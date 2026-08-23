package net.minecraft.client.renderer;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

@OnlyIn(Dist.CLIENT)
public class Projection {
    private ProjectionType projectionType = ProjectionType.PERSPECTIVE;
    private float zNear;
    private float zFar;
    private float perspectiveFov;
    private float width;
    private float height;
    private boolean orthoInvertY;
    private boolean isMatrixDirty;
    // MODIFIED for porting: iris UndoReverseZFour @Unique field - whether the last matrix was built for a shader pack
    private boolean iris$lastShader;

    // MODIFIED for porting: was iris's UndoReverseZFour#iris$cache / #iris$cache2 (@Inject HEAD of setupPerspective and
    // setupOrtho) - the cached matrix has to be rebuilt when the pack state changed, because the near/far planes are swapped.
    private void iris$markDirtyOnShaderChange() {
        if (!net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            return;
        }

        boolean shader = net.irisshaders.iris.Iris.isPackInUseQuick() && !net.irisshaders.iris.vertices.ImmediateState.ALWAYS_REVERSE;
        if (this.iris$lastShader != shader) {
            this.iris$lastShader = shader;
            this.isMatrixDirty = true;
        }
    }
    private final Matrix4f matrix = new Matrix4f();
    private long matrixVersion = -1L;

    public void setupPerspective(final float zNear, final float zFar, final float fov, final float width, final float height) {
        this.iris$markDirtyOnShaderChange(); // MODIFIED for porting: iris UndoReverseZFour#iris$cache
        if (this.projectionType != ProjectionType.PERSPECTIVE
            || this.zNear != zNear
            || this.zFar != zFar
            || this.perspectiveFov != fov
            || this.width != width
            || this.height != height) {
            this.isMatrixDirty = true;
            this.projectionType = ProjectionType.PERSPECTIVE;
            this.zNear = zNear;
            this.zFar = zFar;
            this.perspectiveFov = fov;
            this.width = width;
            this.height = height;
        }
    }

    public void setupOrtho(final float zNear, final float zFar, final float width, final float height, final boolean invertY) {
        this.iris$markDirtyOnShaderChange(); // MODIFIED for porting: iris UndoReverseZFour#iris$cache2
        if (this.projectionType != ProjectionType.ORTHOGRAPHIC
            || this.zNear != zNear
            || this.zFar != zFar
            || this.width != width
            || this.height != height
            || this.orthoInvertY != invertY) {
            this.isMatrixDirty = true;
            this.projectionType = ProjectionType.ORTHOGRAPHIC;
            this.zNear = zNear;
            this.zFar = zFar;
            this.perspectiveFov = 0.0F;
            this.width = width;
            this.height = height;
            this.orthoInvertY = invertY;
        }
    }

    public void setSize(final float width, final float height) {
        this.isMatrixDirty = true;
        this.width = width;
        this.height = height;
    }

    public Matrix4f getMatrix(final Matrix4f dest) {
        if (!this.isMatrixDirty) {
            return dest.set(this.matrix);
        }

        this.isMatrixDirty = false;
        this.matrixVersion++;
        float near = this.zFar;
        float far = this.zNear;
        boolean zZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        // MODIFIED for porting: was iris's UndoReverseZFour#iris$setPerspective / #iris$setOrtho (@Redirect on
        // Matrix4f#setPerspective / #setOrtho) - with a pack loaded the swapped near/far planes are swapped back and the
        // [0, 1] depth range is turned off.
        boolean irisShader = net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled() && net.irisshaders.iris.Iris.isPackInUseQuick() && !net.irisshaders.iris.vertices.ImmediateState.ALWAYS_REVERSE;
        float effectiveNear = irisShader ? far : near;
        float effectiveFar = irisShader ? near : far;
        boolean effectiveZZeroToOne = zZeroToOne && !irisShader;
        return this.projectionType == ProjectionType.PERSPECTIVE
            ? dest.set(
                this.matrix
                    .setPerspective(
                        this.perspectiveFov * (float) (Math.PI / 180.0), this.width / this.height, effectiveNear, effectiveFar, effectiveZZeroToOne
                    )
            )
            : dest.set(
                this.matrix
                    .setOrtho(
                        0.0F,
                        this.width,
                        this.orthoInvertY ? this.height : 0.0F,
                        this.orthoInvertY ? 0.0F : this.height,
                        effectiveNear,
                        effectiveFar,
                        effectiveZZeroToOne
                    )
            );
    }

    public long getMatrixVersion() {
        return this.isMatrixDirty ? this.matrixVersion + 1L : this.matrixVersion;
    }

    public float zNear() {
        return this.zNear;
    }

    public float zFar() {
        return this.zFar;
    }

    public float width() {
        return this.width;
    }

    public float height() {
        return this.height;
    }

    public float fov() {
        return this.perspectiveFov;
    }

    public boolean invertY() {
        return this.orthoInvertY;
    }
}