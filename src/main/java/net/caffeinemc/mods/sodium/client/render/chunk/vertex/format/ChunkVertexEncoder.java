package net.caffeinemc.mods.sodium.client.render.chunk.vertex.format;

public interface ChunkVertexEncoder {
    long write(long ptr, int materialBits, Vertex[] vertices, int sectionIndex);

    // MODIFIED for porting: was iris's compat.sodium MixinChunkVertex (its ChunkVertexExtension implementation)
    class Vertex implements net.irisshaders.iris.vertices.sodium.terrain.ChunkVertexExtension {
        private byte iris$blockEmission;

        private int iris$blockId;

        private byte iris$renderType;

        private int iris$localPosX;

        private int iris$localPosY;

        private int iris$localPosZ;

        private boolean iris$ignoresMidBlock = false;

        @Override
        public void iris$setData(
            final byte blockEmission, final byte renderType, final int blockId, final int localX, final int localY, final int localZ
        ) {
            this.iris$blockEmission = blockEmission;
            this.iris$renderType = renderType;
            this.iris$blockId = blockId;
            this.iris$localPosX = localX;
            this.iris$localPosY = localY;
            this.iris$localPosZ = localZ;
        }

        @Override
        public void iris$ignoresMidBlock(final boolean setIgnore) {
            this.iris$ignoresMidBlock = setIgnore;
        }

        @Override
        public void iris$copyData(final net.irisshaders.iris.vertices.sodium.terrain.ChunkVertexExtension dest) {
            dest.iris$setData(
                this.iris$blockEmission, this.iris$renderType, this.iris$blockId, this.iris$localPosX, this.iris$localPosY, this.iris$localPosZ
            );
        }

        @Override
        public int getLocalPosX() {
            return this.iris$localPosX;
        }

        @Override
        public int getLocalPosY() {
            return this.iris$localPosY;
        }

        @Override
        public int getLocalPosZ() {
            return this.iris$localPosZ;
        }

        @Override
        public int getBlockId() {
            return this.iris$blockId;
        }

        @Override
        public byte getRenderType() {
            return this.iris$renderType;
        }

        @Override
        public byte getBlockEmission() {
            return this.iris$blockEmission;
        }

        @Override
        public boolean ignoreMidBlock() {
            return this.iris$ignoresMidBlock;
        }

        public float x;
        public float y;
        public float z;
        public int color;
        public float ao;
        public float u;
        public float v;
        public int light;

        public static Vertex[] uninitializedQuad() {
            Vertex[] vertices = new Vertex[4];

            for (int i = 0; i < 4; i++) {
                vertices[i] = new Vertex();
            }

            return vertices;
        }

        public static void copyVertexTo(Vertex from, Vertex to) {
            // MODIFIED for porting: was iris's compat.sodium MixinChunkVertex#iris$copyVertex (@Inject HEAD)
            if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
                from.iris$copyData(to);
            }

            to.x = from.x;
            to.y = from.y;
            to.z = from.z;
            to.color = from.color;
            to.ao = from.ao;
            to.u = from.u;
            to.v = from.v;
            to.light = from.light;
        }

        public static void writeVertex(ChunkVertexEncoder.Vertex targetA, float newX, float newY, float newZ, int newColor, float newAo, float newU, float newV, int newLight) {
            targetA.x = newX;
            targetA.y = newY;
            targetA.z = newZ;
            targetA.color = newColor;
            targetA.ao = newAo;
            targetA.u = newU;
            targetA.v = newV;
            targetA.light = newLight;
        }
    }
}
