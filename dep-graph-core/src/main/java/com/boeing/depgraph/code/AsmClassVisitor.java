package com.boeing.depgraph.code;

import com.boeing.depgraph.model.FieldRef;
import com.boeing.depgraph.model.MethodRef;
import com.boeing.depgraph.model.TypeKind;
import com.boeing.depgraph.model.TypeRef;
import com.boeing.depgraph.model.TypeRelation;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * Reads one class file and reports every type it depends on.
 *
 * <h2>The map from bytecode to {@link TypeRelation}</h2>
 * <pre>
 *   visit(superName, interfaces)     EXTENDS, IMPLEMENTS, PERMITS
 *   visitOuterClass / visitInnerClass NESTED_IN
 *   visitAnnotation                  ANNOTATED_BY
 *   class/field/method Signature     TYPE_BOUND, TYPE_ARGUMENT
 *   visitField                       FIELD_TYPE
 *   visitMethod descriptor           PARAM, RETURNS
 *   visitMethod exceptions           THROWS
 *   visitTypeInsn NEW                INSTANTIATES
 *   visitTypeInsn CHECKCAST/INSTANCEOF CASTS_TO
 *   visitMethodInsn                  CALLS
 *   visitFieldInsn GETx or PUTx      READS_FIELD, WRITES_FIELD
 *   visitTryCatchBlock               CATCHES
 *   visitLdcInsn(Type) / Handle      REFERENCES
 * </pre>
 *
 * <h2>Two filters that matter</h2>
 * <b>Synthetic and bridge members are skipped.</b> javac generates them for covariant
 * overrides, lambdas, enum switch maps and outer-instance access; their descriptors name
 * types the author never wrote, and counting them produces edges nobody can find in the
 * source.
 *
 * <p><b>Third-party classes are read surface-only.</b> When {@code thirdParty} is set,
 * method bodies are not visited at all — no {@code CALLS}, no {@code INSTANTIATES}, none
 * of the {@link TypeRelation.Tier#BODY} tier. Nobody is refactoring inside a dependency,
 * and skipping bodies is what keeps a scan of a few hundred jars finite.
 */
class AsmClassVisitor extends ClassVisitor {

    private static final int API = Opcodes.ASM9;

    private final TypeSink sink;
    private final String originPath;
    private final boolean thirdParty;

    private String fqn;

    AsmClassVisitor(TypeSink sink, String originPath, boolean thirdParty) {
        super(API);
        this.sink = sink;
        this.originPath = originPath;
        this.thirdParty = thirdParty;
    }

    // ------------------------------------------------------------------ class

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        this.fqn = fqn(name);

        sink.addType(new TypeRef(
            fqn,
            simpleName(fqn),
            packageName(fqn),
            kindOf(access, superName),
            (access & Opcodes.ACC_PUBLIC) != 0,
            (access & Opcodes.ACC_ABSTRACT) != 0,
            null,
            originPath,
            thirdParty));

        // java.lang.Object and java.lang.Record carry no information; the type filter
        // drops them along with the rest of the JDK.
        if (superName != null) {
            sink.edge(fqn, fqn(superName), TypeRelation.EXTENDS, null);
        }
        if (interfaces != null) {
            for (String iface : interfaces) {
                sink.edge(fqn, fqn(iface), TypeRelation.IMPLEMENTS, null);
            }
        }
        // Class-level generics: `class Repo<T extends Order>` puts Order only here.
        readSignature(signature, TypeRelation.TYPE_BOUND, null);
    }

    /** {@code sealed ... permits B}. */
    @Override
    public void visitPermittedSubclass(String permittedSubclass) {
        sink.edge(fqn, fqn(permittedSubclass), TypeRelation.PERMITS, null);
    }

    /**
     * {@code NESTED_IN} is emitted from the inner class's side only.
     *
     * <p>{@code visitInnerClass} fires on both the outer and the inner class file, so
     * without the name check every nesting would be recorded twice and once backwards.
     */
    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
        if (outerName != null && fqn(name).equals(fqn)) {
            sink.edge(fqn, fqn(outerName), TypeRelation.NESTED_IN, null);
        }
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        annotation(descriptor, null);
        return null;
    }

    // ------------------------------------------------------------------ members

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor,
                                   String signature, Object value) {
        if (isSynthetic(access)) {
            return null;
        }
        String typeFqn = typeOf(descriptor);
        sink.addField(new FieldRef(
            fqn, name, typeFqn,
            (access & Opcodes.ACC_PUBLIC) != 0,
            (access & Opcodes.ACC_STATIC) != 0,
            // A ConstantValue attribute means javac will inline this into every caller
            // and the reference will not survive. See TypeRelation's blind-spot notes.
            value != null && (access & Opcodes.ACC_FINAL) != 0));

        sink.edge(fqn, typeFqn, TypeRelation.FIELD_TYPE, name);
        readSignature(signature, TypeRelation.TYPE_ARGUMENT, name);

        return new FieldVisitor(API) {
            @Override
            public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                annotation(annotationDescriptor, name);
                return null;
            }
        };
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        if (isSynthetic(access)) {
            return null;
        }

        sink.addMethod(new MethodRef(
            fqn, name, descriptor,
            (access & Opcodes.ACC_PUBLIC) != 0,
            (access & Opcodes.ACC_STATIC) != 0,
            (access & Opcodes.ACC_ABSTRACT) != 0));

        // Constructors are methods named <init>; a parameter couples the same either way.
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            sink.edge(fqn, typeOf(argument), TypeRelation.PARAM, name);
        }
        sink.edge(fqn, typeOf(Type.getReturnType(descriptor)), TypeRelation.RETURNS, name);

        if (exceptions != null) {
            for (String thrown : exceptions) {
                sink.edge(fqn, fqn(thrown), TypeRelation.THROWS, name);
            }
        }
        // List<Order> as a parameter: Order lives only in the Signature attribute.
        readSignature(signature, TypeRelation.TYPE_ARGUMENT, name);

        return thirdParty ? annotationsOnly(name) : new BodyVisitor(name);
    }

    /**
     * For a third-party class, annotations on the signature still count; the body does not.
     */
    private MethodVisitor annotationsOnly(String methodName) {
        return new MethodVisitor(API) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                annotation(descriptor, methodName);
                return null;
            }
        };
    }

    // ------------------------------------------------------------------ bodies

    /** Everything in {@link TypeRelation.Tier#BODY}. Project classes only. */
    private final class BodyVisitor extends MethodVisitor {

        private final String methodName;

        private BodyVisitor(String methodName) {
            super(API);
            this.methodName = methodName;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            annotation(descriptor, methodName);
            return null;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            TypeRelation relation = switch (opcode) {
                case Opcodes.NEW -> TypeRelation.INSTANTIATES;
                case Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> TypeRelation.CASTS_TO;
                // ANEWARRAY and everything else: a real reference, a weak one.
                default -> TypeRelation.REFERENCES;
            };
            sink.edge(fqn, fqn(type), relation, methodName);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            // `new Foo()` is NEW + invokespecial <init>; NEW already recorded the
            // instantiation, so the constructor call is not also counted as a CALLS.
            if (!"<init>".equals(name)) {
                sink.edge(fqn, fqn(owner), TypeRelation.CALLS, methodName);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            boolean write = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
            sink.edge(fqn, fqn(owner),
                write ? TypeRelation.WRITES_FIELD : TypeRelation.READS_FIELD, methodName);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            // A null type is a finally block, which catches nothing nameable.
            if (type != null) {
                sink.edge(fqn, fqn(type), TypeRelation.CATCHES, methodName);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof Type type) {
                sink.edge(fqn, typeOf(type), TypeRelation.REFERENCES, methodName);
            } else if (value instanceof Handle handle) {
                sink.edge(fqn, fqn(handle.getOwner()), TypeRelation.REFERENCES, methodName);
            }
        }

        /**
         * Lambdas and method references arrive as an invokedynamic whose bootstrap
         * arguments name the real target. Without this every lambda body would be
         * invisible.
         */
        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor,
                                           Handle bootstrapMethodHandle,
                                           Object... bootstrapMethodArguments) {
            for (Object argument : bootstrapMethodArguments) {
                if (argument instanceof Handle handle) {
                    sink.edge(fqn, fqn(handle.getOwner()), TypeRelation.CALLS, methodName);
                } else if (argument instanceof Type type && type.getSort() == Type.METHOD) {
                    for (Type arg : type.getArgumentTypes()) {
                        sink.edge(fqn, typeOf(arg), TypeRelation.REFERENCES, methodName);
                    }
                    sink.edge(fqn, typeOf(type.getReturnType()), TypeRelation.REFERENCES, methodName);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void annotation(String descriptor, String viaMember) {
        sink.edge(fqn, typeOf(descriptor), TypeRelation.ANNOTATED_BY, viaMember);
    }

    /**
     * Walk a generic signature and record every class name in it.
     *
     * <p>Descriptors are erased — {@code List<Order>} is only {@code List} — so anything
     * inside the angle brackets exists solely here. Where javac wrote no signature
     * attribute (local variables, and non-generic members) there is nothing to read and
     * the relation is simply absent, which is the erasure blind spot made visible.
     */
    private void readSignature(String signature, TypeRelation relation, String viaMember) {
        if (signature == null) {
            return;
        }
        new SignatureReader(signature).accept(new SignatureVisitor(API) {
            @Override
            public void visitClassType(String name) {
                sink.edge(fqn, fqn(name), relation, viaMember);
            }
        });
    }

    /** javac's own members, not the author's. */
    private static boolean isSynthetic(int access) {
        return (access & Opcodes.ACC_SYNTHETIC) != 0 || (access & Opcodes.ACC_BRIDGE) != 0;
    }

    private static TypeKind kindOf(int access, String superName) {
        if ((access & Opcodes.ACC_ANNOTATION) != 0) {
            return TypeKind.ANNOTATION;
        }
        if ((access & Opcodes.ACC_INTERFACE) != 0) {
            return TypeKind.INTERFACE;
        }
        if ((access & Opcodes.ACC_ENUM) != 0) {
            return TypeKind.ENUM;
        }
        // The only way to tell a record from a final class: nothing else extends Record.
        return "java/lang/Record".equals(superName) ? TypeKind.RECORD : TypeKind.CLASS;
    }

    /** Internal name to fully qualified name. */
    private static String fqn(String internalName) {
        return internalName == null ? null : internalName.replace('/', '.');
    }

    /** A descriptor's type, unwrapping arrays and dropping primitives. */
    private static String typeOf(String descriptor) {
        return typeOf(Type.getType(descriptor));
    }

    private static String typeOf(Type type) {
        Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        // int[][] depends on nothing; Order[] depends on Order.
        return element.getSort() == Type.OBJECT ? element.getClassName() : null;
    }

    private static String simpleName(String fqn) {
        int lastDot = Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('$'));
        return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
    }

    private static String packageName(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? "" : fqn.substring(0, lastDot);
    }
}
