package lagfinder.mixins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.spongepowered.asm.lib.Handle;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.Type;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.InsnList;
import org.spongepowered.asm.lib.tree.InsnNode;
import org.spongepowered.asm.lib.tree.IntInsnNode;
import org.spongepowered.asm.lib.tree.InvokeDynamicInsnNode;
import org.spongepowered.asm.lib.tree.JumpInsnNode;
import org.spongepowered.asm.lib.tree.LabelNode;
import org.spongepowered.asm.lib.tree.LdcInsnNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.lib.tree.TypeInsnNode;
import org.spongepowered.asm.lib.tree.VarInsnNode;

public final class IdleTimingOptimizer {

    private static final String SESSION = "lagfinder/core/ProfileSession";

    private IdleTimingOptimizer() {}

    public static void optimize(ClassNode target) {
        for (MethodNode method : new ArrayList<>(target.methods)) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof InvokeDynamicInsnNode)) {
                    continue;
                }
                InvokeDynamicInsnNode factory = (InvokeDynamicInsnNode) instruction;
                AbstractInsnNode next = executable(factory.getNext());
                if (!(next instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode handler = (MethodInsnNode) next;
                if (!handler.owner.equals(target.name) || !handler.name.contains("$lagfinder$time")
                    || !factory.name.equals("call")
                    || !factory.bsm.getOwner()
                        .equals("java/lang/invoke/LambdaMetafactory")
                    || !factory.bsm.getName()
                        .equals("metafactory")
                    || factory.bsmArgs.length != 3
                    || !(factory.bsmArgs[1] instanceof Handle)) {
                    continue;
                }
                Type[] parameters = Type.getArgumentTypes(handler.desc);
                Type operation = Type.getReturnType(factory.desc);
                if (parameters.length == 0 || !parameters[parameters.length - 1].equals(operation)
                    || !operation.getInternalName()
                        .endsWith("/wrapoperation/Operation")) {
                    continue;
                }
                Type[] arguments = Arrays.copyOf(parameters, parameters.length - 1);
                Type[] captured = Type.getArgumentTypes(factory.desc);
                Type result = Type.getReturnType(handler.desc);
                Handle bridge = (Handle) factory.bsmArgs[1];
                MethodNode direct = directBridge(target, bridge, arguments, captured, result);
                if (direct == null) {
                    continue;
                }
                target.methods.add(direct);
                guard(method, factory, handler, direct, arguments, captured);
            }
        }
    }

    private static MethodNode directBridge(ClassNode owner, Handle bridge, Type[] arguments, Type[] captured,
        Type result) {
        if (!bridge.getOwner()
            .equals(owner.name)
            || (bridge.getTag() != Opcodes.H_INVOKESTATIC && bridge.getTag() != Opcodes.H_INVOKESPECIAL)) {
            return null;
        }
        MethodNode source = null;
        for (MethodNode method : owner.methods) {
            if (method.name.equals(bridge.getName()) && method.desc.equals(bridge.getDesc())) {
                source = method;
                break;
            }
        }
        if (source == null || !source.name.startsWith("mixinextras$bridge$") || !source.tryCatchBlocks.isEmpty()) {
            return null;
        }
        boolean instance = bridge.getTag() == Opcodes.H_INVOKESPECIAL;
        Type[] bound = Type.getArgumentTypes(bridge.getDesc());
        if (bound.length == 0 || !bound[bound.length - 1].equals(Type.getType(Object[].class))
            || captured.length != bound.length - 1 + (instance ? 1 : 0)) {
            return null;
        }
        if (instance && !captured[0].equals(Type.getObjectType(owner.name))) {
            return null;
        }
        for (int i = 0; i < bound.length - 1; i++) {
            if (!bound[i].equals(captured[i + (instance ? 1 : 0)])) {
                return null;
            }
        }
        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode instruction : source.instructions.toArray()) {
            if (instruction.getOpcode() >= 0) {
                code.add(instruction);
            }
        }
        int arraySlot = instance ? 1 : 0;
        for (int i = 0; i < bound.length - 1; i++) {
            arraySlot += bound[i].getSize();
        }
        Cursor cursor = new Cursor(code);
        if (!cursor.variable(Opcodes.ALOAD, arraySlot) || !cursor.integer(arguments.length)
            || !(cursor.take() instanceof LdcInsnNode)
            || !cursor.call(
                Opcodes.INVOKESTATIC,
                "com/llamalad7/mixinextras/injector/wrapoperation/WrapOperationRuntime",
                "checkArgumentCount",
                "([Ljava/lang/Object;ILjava/lang/String;)V")) {
            return null;
        }
        if (instance && !cursor.variable(Opcodes.ALOAD, 0)) {
            return null;
        }
        if (!cursor.variable(Opcodes.ALOAD, arraySlot)) {
            return null;
        }
        for (int i = 0; i < arguments.length; i++) {
            Type argument = arguments[i];
            boolean primitive = argument.getSort() < Type.ARRAY;
            if (!cursor.opcode(Opcodes.DUP) || !cursor.integer(i)
                || !cursor.opcode(Opcodes.AALOAD)
                || !cursor.cast(primitive ? boxed(argument) : argument.getInternalName())) {
                return null;
            }
            if (primitive && !cursor.call(
                Opcodes.INVOKEVIRTUAL,
                boxed(argument),
                argument.getClassName() + "Value",
                "()" + argument.getDescriptor())) {
                return null;
            }
            if (argument.getSize() == 2) {
                if (!cursor.opcode(Opcodes.DUP2_X1) || !cursor.opcode(Opcodes.POP2)) {
                    return null;
                }
            } else if (!cursor.opcode(Opcodes.SWAP)) {
                return null;
            }
        }
        if (!cursor.opcode(Opcodes.POP)) {
            return null;
        }
        int slot = instance ? 1 : 0;
        for (int i = 0; i < bound.length - 1; i++) {
            if (!cursor.variable(bound[i].getOpcode(Opcodes.ILOAD), slot)) {
                return null;
            }
            slot += bound[i].getSize();
        }
        AbstractInsnNode instruction = cursor.take();
        if (!(instruction instanceof MethodInsnNode)) {
            return null;
        }
        MethodInsnNode original = (MethodInsnNode) instruction;
        if (!Type.getReturnType(original.desc)
            .equals(result)) {
            return null;
        }
        if (result.equals(Type.VOID_TYPE)) {
            if (!cursor.opcode(Opcodes.ACONST_NULL) || !cursor.cast("java/lang/Void")) {
                return null;
            }
        } else if (result.getSort() < Type.ARRAY && !cursor.call(
            Opcodes.INVOKESTATIC,
            boxed(result),
            "valueOf",
            "(" + result.getDescriptor() + ")L" + boxed(result) + ";")) {
                return null;
            }
        if (!cursor.opcode(Opcodes.ARETURN) || cursor.remaining()) {
            return null;
        }
        Type[] directArguments = new Type[captured.length + arguments.length];
        System.arraycopy(captured, 0, directArguments, 0, captured.length);
        System.arraycopy(arguments, 0, directArguments, captured.length, arguments.length);
        MethodNode direct = new MethodNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            source.name + "$lagfinder$idle",
            Type.getMethodDescriptor(result, directArguments),
            null,
            null);
        for (MethodNode method : owner.methods) {
            if (method.name.equals(direct.name) && method.desc.equals(direct.desc)) {
                return null;
            }
        }
        int argumentSlot = 0;
        for (Type capture : captured) {
            argumentSlot += capture.getSize();
        }
        if (instance) {
            direct.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        for (Type argument : arguments) {
            direct.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), argumentSlot));
            argumentSlot += argument.getSize();
        }
        slot = instance ? 1 : 0;
        for (int i = 0; i < bound.length - 1; i++) {
            direct.instructions.add(new VarInsnNode(bound[i].getOpcode(Opcodes.ILOAD), slot));
            slot += bound[i].getSize();
        }
        direct.instructions.add(original.clone(null));
        direct.instructions.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        direct.maxLocals = argumentSlot;
        direct.maxStack = argumentSlot + 2;
        return direct;
    }

    private static void guard(MethodNode method, InvokeDynamicInsnNode factory, MethodInsnNode handler,
        MethodNode direct, Type[] arguments, Type[] captured) {
        InsnList before = new InsnList();
        int[] captures = store(before, method, captured);
        int[] values = store(before, method, arguments);
        int receiver = -1;
        if (handler.getOpcode() != Opcodes.INVOKESTATIC) {
            receiver = method.maxLocals++;
            before.add(new VarInsnNode(Opcodes.ASTORE, receiver));
        }
        LabelNode active = new LabelNode();
        LabelNode end = new LabelNode();
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SESSION, "current", "()L" + SESSION + ";", false));
        before.add(new JumpInsnNode(Opcodes.IFNONNULL, active));
        load(before, captured, captures);
        load(before, arguments, values);
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, handler.owner, direct.name, direct.desc, false));
        before.add(new JumpInsnNode(Opcodes.GOTO, end));
        before.add(active);
        if (receiver >= 0) {
            before.add(new VarInsnNode(Opcodes.ALOAD, receiver));
        }
        load(before, arguments, values);
        load(before, captured, captures);
        method.instructions.insertBefore(factory, before);
        method.instructions.insert(handler, end);
    }

    private static int[] store(InsnList code, MethodNode method, Type[] types) {
        int[] slots = new int[types.length];
        for (int i = types.length - 1; i >= 0; i--) {
            slots[i] = method.maxLocals;
            method.maxLocals += types[i].getSize();
            code.add(new VarInsnNode(types[i].getOpcode(Opcodes.ISTORE), slots[i]));
        }
        return slots;
    }

    private static void load(InsnList code, Type[] types, int[] slots) {
        for (int i = 0; i < types.length; i++) {
            code.add(new VarInsnNode(types[i].getOpcode(Opcodes.ILOAD), slots[i]));
        }
    }

    private static AbstractInsnNode executable(AbstractInsnNode instruction) {
        while (instruction != null && instruction.getOpcode() < 0) {
            instruction = instruction.getNext();
        }
        return instruction;
    }

    private static String boxed(Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                return "java/lang/Boolean";
            case Type.BYTE:
                return "java/lang/Byte";
            case Type.CHAR:
                return "java/lang/Character";
            case Type.SHORT:
                return "java/lang/Short";
            case Type.INT:
                return "java/lang/Integer";
            case Type.FLOAT:
                return "java/lang/Float";
            case Type.LONG:
                return "java/lang/Long";
            case Type.DOUBLE:
                return "java/lang/Double";
            default:
                throw new IllegalArgumentException(type.toString());
        }
    }

    private static final class Cursor {

        private final List<AbstractInsnNode> code;
        private int index;

        private Cursor(List<AbstractInsnNode> code) {
            this.code = code;
        }

        private AbstractInsnNode take() {
            return index < code.size() ? code.get(index++) : null;
        }

        private boolean opcode(int opcode) {
            AbstractInsnNode instruction = take();
            return instruction != null && instruction.getOpcode() == opcode;
        }

        private boolean variable(int opcode, int slot) {
            AbstractInsnNode instruction = take();
            return instruction instanceof VarInsnNode && instruction.getOpcode() == opcode
                && ((VarInsnNode) instruction).var == slot;
        }

        private boolean integer(int value) {
            AbstractInsnNode instruction = take();
            return instruction instanceof IntInsnNode && instruction.getOpcode() == Opcodes.BIPUSH
                && ((IntInsnNode) instruction).operand == value;
        }

        private boolean cast(String type) {
            AbstractInsnNode instruction = take();
            return instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.CHECKCAST
                && ((TypeInsnNode) instruction).desc.equals(type);
        }

        private boolean call(int opcode, String owner, String name, String descriptor) {
            AbstractInsnNode instruction = take();
            if (!(instruction instanceof MethodInsnNode)) {
                return false;
            }
            MethodInsnNode call = (MethodInsnNode) instruction;
            return call.getOpcode() == opcode && call.owner.equals(owner)
                && call.name.equals(name)
                && call.desc.equals(descriptor);
        }

        private boolean remaining() {
            return index != code.size();
        }
    }
}
