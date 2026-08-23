package net.caffeinemc.mods.lithium.common.world.section;

import net.caffeinemc.mods.lithium.common.tracking.block.ChunkSectionChangeCallback;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Arrays;

public interface LithiumSectionData {

    class SectionData {
        private short[] countsByFlag;
        private ChunkSectionChangeCallback changeListener;

        private byte[] randomTickableBlocksByY;

        public SectionData(LevelChunkSection chunkSection) {
            this.countsByFlag = null;
            this.changeListener = null;
        }

        public void setCountsByFlag(short[] countsByFlag) {
            this.countsByFlag = countsByFlag;
        }

        public short[] getCountsByFlag() {
            return this.countsByFlag;
        }

        public void setChangeListener(ChunkSectionChangeCallback changeListener) {
            this.changeListener = changeListener;
        }

        public ChunkSectionChangeCallback getChangeListener() {
            return this.changeListener;
        }

        public byte[] getRandomTickableBlocksByY() {
            return this.randomTickableBlocksByY;
        }

        public void setRandomTickableBlocksByY(byte[] randomTickableBlocksByY) {
            if (randomTickableBlocksByY.length != RandomTickingSectionDataHelper.BYTE_COUNT) {
                throw new IllegalArgumentException("Invalid randomTickableBlocksByY length: " + randomTickableBlocksByY.length + ", expected " + RandomTickingSectionDataHelper.BYTE_COUNT);
            }
            this.randomTickableBlocksByY = randomTickableBlocksByY;
        }

        @Override
        public String toString() {
            return "SectionData[" +
                    "countsByFlag=" + Arrays.toString(this.countsByFlag) + ", " +
                    "changeListener=" + this.changeListener + ", " +
                    "randomTickableBlocksByY=" + Arrays.toString(this.randomTickableBlocksByY) + "]";
        }


    }

    LithiumSectionData.SectionData lithium$getSectionData();

    LithiumSectionData.SectionData lithium$getSectionDataDirect();
}
