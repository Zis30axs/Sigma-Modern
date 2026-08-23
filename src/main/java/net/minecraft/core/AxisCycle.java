package net.minecraft.core;

public enum AxisCycle {
    NONE {
        @Override
        public int cycle(final int x, final int y, final int z, final Direction.Axis axis) {
            return axis.choose(x, y, z);
        }

        @Override
        public double cycle(final double x, final double y, final double z, final Direction.Axis axis) {
            return axis.choose(x, y, z);
        }

        @Override
        public Direction.Axis cycle(final Direction.Axis axis) {
            return axis;
        }

        @Override
        public AxisCycle inverse() {
            return this;
        }
    },
    FORWARD {
        @Override
        public int cycle(final int x, final int y, final int z, final Direction.Axis axis) {
            return axis.choose(z, x, y);
        }

        @Override
        public double cycle(final double x, final double y, final double z, final Direction.Axis axis) {
            return axis.choose(z, x, y);
        }

        // MODIFIED for porting: lithium math.fast_util AxisCycleDirectionMixin$ForwardMixin replaces the
        // array+modulo lookup by a switch on the axis ordinal.
        @Override
        public Direction.Axis cycle(final Direction.Axis axis) {
            return switch (axis.ordinal()) {
                case 0 -> Direction.Axis.Y;
                case 1 -> Direction.Axis.Z;
                case 2 -> Direction.Axis.X;
                default -> throw new IllegalArgumentException();
            };
        }

        @Override
        public AxisCycle inverse() {
            return BACKWARD;
        }
    },
    BACKWARD {
        @Override
        public int cycle(final int x, final int y, final int z, final Direction.Axis axis) {
            return axis.choose(y, z, x);
        }

        @Override
        public double cycle(final double x, final double y, final double z, final Direction.Axis axis) {
            return axis.choose(y, z, x);
        }

        // MODIFIED for porting: lithium math.fast_util AxisCycleDirectionMixin$BackwardMixin (see FORWARD above)
        @Override
        public Direction.Axis cycle(final Direction.Axis axis) {
            return switch (axis.ordinal()) {
                case 0 -> Direction.Axis.Z;
                case 1 -> Direction.Axis.X;
                case 2 -> Direction.Axis.Y;
                default -> throw new IllegalArgumentException();
            };
        }

        @Override
        public AxisCycle inverse() {
            return FORWARD;
        }
    };

    public static final Direction.Axis[] AXIS_VALUES = Direction.Axis.values();
    public static final AxisCycle[] VALUES = values();

    public abstract int cycle(final int x, final int y, final int z, final Direction.Axis axis);

    public abstract double cycle(final double x, final double y, final double z, final Direction.Axis axis);

    public abstract Direction.Axis cycle(final Direction.Axis axis);

    public abstract AxisCycle inverse();

    public static AxisCycle between(final Direction.Axis from, final Direction.Axis to) {
        return VALUES[Math.floorMod(to.ordinal() - from.ordinal(), 3)];
    }
}