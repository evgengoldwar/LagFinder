package lagfinder.mixins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.spongepowered.asm.lib.ClassWriter;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.Type;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.InsnList;
import org.spongepowered.asm.lib.tree.InsnNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.lib.tree.TypeInsnNode;
import org.spongepowered.asm.lib.tree.VarInsnNode;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.utils.OperationUtils;

public class IdleTimingOptimizerTest {

    private static final String FIXTURE = "lagfinder/mixins/TimingFixture";
    private static final String SESSION = "lagfinder/core/ProfileSession";
    private static final String SUPPORT = Type.getInternalName(Support.class);
    private static final Type OPERATION = Type.getType(Operation.class);

    public static final class Support {

        static int calls, wrappers, finishes;
        static boolean throwNext;
        static Object block, world, random;
        static int x, y, z;

        public static Object invoke(Operation<?> operation, Object[] arguments) {
            wrappers++;
            try {
                return operation.call(arguments);
            } finally {
                finishes++;
            }
        }

        private static void called() {
            calls++;
            if (throwNext) {
                throw new IllegalStateException("original failure");
            }
        }

        public static void blockTick(Object blockArgument, Object worldArgument, int a, int b, int c,
            Object randomArgument) {
            called();
            block = blockArgument;
            world = worldArgument;
            random = randomArgument;
            x = a;
            y = b;
            z = c;
        }

        public static boolean numbers(long number, double fraction) {
            called();
            return number == 1234567890123L && fraction == 0.125;
        }

        public static int bound(int value, long captured) {
            called();
            return value + (int) captured;
        }

        public static Object reference(Object value) {
            called();
            return value;
        }

        public static boolean render(Object entity, float partialTicks) {
            called();
            return entity != null && partialTicks == 0.25f;
        }

        public static void empty() {
            called();
        }
    }

    @Before
    public void reset() {
        Support.calls = Support.wrappers = Support.finishes = 0;
        Support.throwNext = false;
    }

    @Test
    public void bypassesIdleBlockTimingWithoutLosingCoordinatesOrOtherModCalls() throws Exception {
        Fixture fixture = fixture("blockTick", false, new Type[0]);
        Object block = new Object(), world = new Object(), random = new Object();
        fixture.run(block, world, -1234567, 255, 9876543, random);
        assertEquals(1, Support.calls);
        assertEquals(0, Support.wrappers);
        assertSame(block, Support.block);
        assertSame(world, Support.world);
        assertSame(random, Support.random);
        assertEquals(-1234567, Support.x);
        assertEquals(255, Support.y);
        assertEquals(9876543, Support.z);
        fixture.active(true);
        fixture.run(block, world, -1234567, 255, 9876543, random);
        assertEquals(2, Support.calls);
        assertEquals(1, Support.wrappers);
        assertEquals(1, Support.finishes);
    }

    @Test
    public void preservesExceptionsAndActiveFinallyBlocks() throws Exception {
        Fixture fixture = fixture("blockTick", true, new Type[0]);
        Support.throwNext = true;
        for (boolean active : new boolean[] { false, true }) {
            fixture.active(active);
            try {
                fixture.run(new Object(), new Object(), -5000, 70, 9000, new Object());
                fail("Original exception was swallowed");
            } catch (InvocationTargetException exception) {
                assertEquals(
                    IllegalStateException.class,
                    exception.getCause()
                        .getClass());
                assertEquals(
                    "original failure",
                    exception.getCause()
                        .getMessage());
            }
        }
        assertEquals(2, Support.calls);
        assertEquals(1, Support.wrappers);
        assertEquals(1, Support.finishes);
    }

    @Test
    public void preservesWidePrimitiveArgumentsAndBooleanResults() throws Exception {
        Fixture fixture = fixture("numbers", false, new Type[0]);
        for (boolean active : new boolean[] { false, true, false }) {
            fixture.active(active);
            assertEquals(Boolean.TRUE, fixture.run(1234567890123L, 0.125));
        }
        assertEquals(3, Support.calls);
        assertEquals(1, Support.wrappers);
    }

    @Test
    public void preservesInstanceReceiverAndCapturedArgumentsOfOtherWrappers() throws Exception {
        Fixture fixture = fixture("bound", true, new Type[] { Type.LONG_TYPE });
        for (boolean active : new boolean[] { false, true }) {
            fixture.active(active);
            assertEquals(1005, fixture.run(-2000, 3005L));
        }
        assertEquals(2, Support.calls);
        assertEquals(1, Support.wrappers);
    }

    @Test
    public void preservesReferenceResults() throws Exception {
        Fixture fixture = fixture("reference", false, new Type[0]);
        Object reference = new Object();
        for (boolean active : new boolean[] { false, true }) {
            fixture.active(active);
            assertSame(reference, fixture.run(reference));
        }
        assertEquals(2, Support.calls);
        assertEquals(1, Support.wrappers);
    }

    @Test
    public void preservesClientRenderArgumentsAndResults() throws Exception {
        Fixture fixture = fixture("render", false, new Type[0]);
        for (boolean active : new boolean[] { false, true }) {
            fixture.active(active);
            assertEquals(Boolean.TRUE, fixture.run(new Object(), 0.25f));
        }
        assertEquals(2, Support.calls);
        assertEquals(1, Support.wrappers);
    }

    @Test
    public void supportsOperationsWithoutArguments() throws Exception {
        Fixture fixture = fixture("empty", false, new Type[0]);
        for (boolean active : new boolean[] { false, true }) {
            fixture.active(active);
            fixture.run();
        }
        assertEquals(2, Support.calls);
        assertEquals(1, Support.wrappers);
    }

    @Test
    public void leavesUnknownBridgeLayoutsAndUnrelatedWrappersUntouched() throws Exception {
        ClassNode target = target("blockTick", false, new Type[0]);
        MethodNode bridge = target.methods.get(target.methods.size() - 1);
        bridge.instructions.insert(new InsnNode(Opcodes.NOP));
        int methods = target.methods.size();
        IdleTimingOptimizer.optimize(target);
        assertEquals(methods, target.methods.size());
        target = target("blockTick", false, new Type[0]);
        for (MethodNode method : target.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.name.contains("$lagfinder$time")) {
                        call.name = "wrapOperation$otherMod$timeBlockTick";
                    }
                }
            }
        }
        IdleTimingOptimizer.optimize(target);
        assertEquals(methods, target.methods.size());
    }

    @Test
    public void generatesAllocationFreeTypedBridgesAndIsIdempotent() throws Exception {
        ClassNode target = target("blockTick", true, new Type[0]);
        int methods = target.methods.size();
        IdleTimingOptimizer.optimize(target);
        assertEquals(methods + 1, target.methods.size());
        MethodNode direct = target.methods.get(methods);
        assertTrue(direct.name.endsWith("$lagfinder$idle"));
        for (AbstractInsnNode instruction : direct.instructions.toArray()) {
            assertFalse(instruction.getOpcode() == Opcodes.ANEWARRAY);
            assertFalse(instruction.getOpcode() == Opcodes.NEW);
            assertFalse(instruction.getOpcode() == Opcodes.INVOKEDYNAMIC);
            if (instruction instanceof MethodInsnNode) {
                assertFalse(((MethodInsnNode) instruction).name.equals("valueOf"));
            }
        }
        IdleTimingOptimizer.optimize(target);
        assertEquals(methods + 1, target.methods.size());
    }

    private static Fixture fixture(String callback, boolean instanceBridge, Type[] captured) throws Exception {
        ClassNode target = target(callback, instanceBridge, captured);
        int methods = target.methods.size();
        IdleTimingOptimizer.optimize(target);
        assertEquals(methods + 1, target.methods.size());
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(FIXTURE.replace('/', '.'), bytes(target));
        ClassNode session = node(SESSION);
        session.fields.add(
            new org.spongepowered.asm.lib.tree.FieldNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "active",
                "Z",
                null,
                null));
        MethodNode current = new MethodNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "current",
            "()L" + SESSION + ";",
            null,
            null);
        current.instructions
            .add(new org.spongepowered.asm.lib.tree.FieldInsnNode(Opcodes.GETSTATIC, SESSION, "active", "Z"));
        org.spongepowered.asm.lib.tree.LabelNode idle = new org.spongepowered.asm.lib.tree.LabelNode();
        current.instructions.add(new org.spongepowered.asm.lib.tree.JumpInsnNode(Opcodes.IFEQ, idle));
        current.instructions.add(new TypeInsnNode(Opcodes.NEW, SESSION));
        current.instructions.add(new InsnNode(Opcodes.DUP));
        current.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, SESSION, "<init>", "()V", false));
        current.instructions.add(new InsnNode(Opcodes.ARETURN));
        current.instructions.add(idle);
        current.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        current.instructions.add(new InsnNode(Opcodes.ARETURN));
        session.methods.add(current);
        classes.put(SESSION.replace('/', '.'), bytes(session));
        ClassLoader loader = new ClassLoader(IdleTimingOptimizerTest.class.getClassLoader()) {

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                byte[] code = classes.get(name);
                if (code == null) {
                    return super.loadClass(name, resolve);
                }
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = defineClass(name, code, 0, code.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        };
        Class<?> type = loader.loadClass(FIXTURE.replace('/', '.'));
        return new Fixture(
            type.getConstructor()
                .newInstance(),
            loader.loadClass(SESSION.replace('/', '.')));
    }

    private static ClassNode target(String callback, boolean instanceBridge, Type[] captured) throws Exception {
        Method support = null;
        for (Method method : Support.class.getMethods()) {
            if (method.getName()
                .equals(callback)) {
                support = method;
                break;
            }
        }
        String descriptor = Type.getMethodDescriptor(support);
        Type[] allArguments = Type.getArgumentTypes(descriptor);
        Type[] arguments = java.util.Arrays.copyOf(allArguments, allArguments.length - captured.length);
        Type result = Type.getReturnType(descriptor);
        ClassNode target = node(FIXTURE);
        MethodNode original = new MethodNode(
            Opcodes.ACC_PRIVATE | (instanceBridge ? 0 : Opcodes.ACC_STATIC),
            "otherMod$original",
            descriptor,
            null,
            null);
        load(original.instructions, allArguments, instanceBridge ? 1 : 0);
        original.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SUPPORT, callback, descriptor, false));
        original.instructions.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        target.methods.add(original);
        Type[] handlerArguments = java.util.Arrays.copyOf(arguments, arguments.length + 1);
        handlerArguments[arguments.length] = OPERATION;
        MethodNode handler = new MethodNode(
            Opcodes.ACC_PRIVATE,
            "wrapOperation$fixture$lagfinder$timeBlockTick",
            Type.getMethodDescriptor(result, handlerArguments),
            null,
            null);
        int operationSlot = 1;
        for (Type argument : arguments) {
            operationSlot += argument.getSize();
        }
        handler.instructions.add(new VarInsnNode(Opcodes.ALOAD, operationSlot));
        handler.instructions.add(new org.spongepowered.asm.lib.tree.IntInsnNode(Opcodes.BIPUSH, arguments.length));
        handler.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int slot = 1;
        for (int i = 0; i < arguments.length; i++) {
            Type argument = arguments[i];
            handler.instructions.add(new InsnNode(Opcodes.DUP));
            handler.instructions.add(new org.spongepowered.asm.lib.tree.IntInsnNode(Opcodes.BIPUSH, i));
            handler.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            if (argument.getSort() < Type.ARRAY) {
                String boxed = box(argument);
                handler.instructions.add(
                    new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        boxed,
                        "valueOf",
                        "(" + argument.getDescriptor() + ")L" + boxed + ";",
                        false));
            }
            handler.instructions.add(new InsnNode(Opcodes.AASTORE));
            slot += argument.getSize();
        }
        handler.instructions.add(
            new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                SUPPORT,
                "invoke",
                "(" + OPERATION.getDescriptor() + "[Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        if (result.equals(Type.VOID_TYPE)) {
            handler.instructions.add(new InsnNode(Opcodes.POP));
        } else if (result.getSort() < Type.ARRAY) {
            handler.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, box(result)));
            handler.instructions.add(
                new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    box(result),
                    result.getClassName() + "Value",
                    "()" + result.getDescriptor(),
                    false));
        } else {
            handler.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, result.getInternalName()));
        }
        handler.instructions.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        target.methods.add(handler);
        MethodNode run = new MethodNode(Opcodes.ACC_PUBLIC, "run", descriptor, null, null);
        run.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        load(run.instructions, arguments, 1);
        if (instanceBridge) {
            run.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        load(run.instructions, captured, operationSlot);
        target.methods.add(run);
        OperationUtils.makeOperation(
            arguments,
            result,
            run.instructions,
            instanceBridge,
            captured,
            target,
            OPERATION,
            "fixture",
            (array, loadArguments) -> {
                InsnList code = new InsnList();
                loadArguments.accept(code);
                code.add(
                    new MethodInsnNode(
                        instanceBridge ? Opcodes.INVOKESPECIAL : Opcodes.INVOKESTATIC,
                        FIXTURE,
                        original.name,
                        original.desc,
                        false));
                return code;
            });
        run.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, FIXTURE, handler.name, handler.desc, false));
        run.instructions.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        run.maxLocals = 1;
        for (Type argument : allArguments) {
            run.maxLocals += argument.getSize();
        }
        return target;
    }

    private static ClassNode node(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions
            .add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(constructor);
        return node;
    }

    private static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void load(InsnList code, Type[] arguments, int slot) {
        for (Type argument : arguments) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
        }
    }

    private static String box(Type type) {
        if (type.equals(Type.INT_TYPE)) {
            return "java/lang/Integer";
        }
        if (type.equals(Type.LONG_TYPE)) {
            return "java/lang/Long";
        }
        if (type.equals(Type.DOUBLE_TYPE)) {
            return "java/lang/Double";
        }
        if (type.equals(Type.BOOLEAN_TYPE)) {
            return "java/lang/Boolean";
        }
        if (type.equals(Type.FLOAT_TYPE)) {
            return "java/lang/Float";
        }
        throw new IllegalArgumentException(type.toString());
    }

    private static final class Fixture {

        private final Object instance;
        private final Class<?> session;

        private Fixture(Object instance, Class<?> session) {
            this.instance = instance;
            this.session = session;
        }

        private void active(boolean active) throws Exception {
            session.getField("active")
                .setBoolean(null, active);
        }

        private Object run(Object... arguments) throws Exception {
            for (Method method : instance.getClass()
                .getMethods()) {
                if (method.getName()
                    .equals("run")) {
                    return method.invoke(instance, arguments);
                }
            }
            throw new NoSuchMethodException("run");
        }
    }
}
