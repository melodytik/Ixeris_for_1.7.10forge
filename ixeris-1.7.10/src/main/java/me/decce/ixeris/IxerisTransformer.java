package me.decce.ixeris;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * ASM bytecode transformer for Ixeris 1.7.10.
 *
 * Transforms four classes:
 *
 * 1. net.minecraft.client.Minecraft — injects thread-split logic at the head
 *    of the game loop method (run / func_71411_J).
 *
 * 2. org.lwjgl.input.Mouse — redirects all input-querying methods to
 *    {@link InputManager} when called on the render thread.
 *
 * 3. org.lwjgl.input.Keyboard — same redirect pattern.
 *
 * 4. org.lwjgl.opengl.Display — makes processMessages() a no-op on the
 *    render thread, and redirects isCloseRequested()/wasResized() to
 *    volatile mirrors.
 */
public class IxerisTransformer implements IClassTransformer, Opcodes {

    private static final String IXERIS = "me/decce/ixeris/Ixeris";
    private static final String INPUT_MGR = "me/decce/ixeris/InputManager";
    private static final String THREAD = "java/lang/Thread";

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null) return null;

        // Normalise name — LaunchWrapper may pass '/' or '.' separators
        // depending on version, so we handle both.
        String dotName = (transformedName != null) ? transformedName.replace('/', '.') : "";
        String slashName = (transformedName != null) ? transformedName.replace('.', '/') : "";

        try {
            if (dotName.equals("net.minecraft.client.Minecraft") ||
                slashName.equals("net/minecraft/client/Minecraft")) {
                System.out.println("[Ixeris] Patching Minecraft (" + transformedName + ")");
                return transformMinecraft(bytes);
            } else if (dotName.equals("org.lwjgl.input.Mouse") ||
                       slashName.equals("org/lwjgl/input/Mouse")) {
                System.out.println("[Ixeris] Patching Mouse (" + transformedName + ")");
                return transformMouse(bytes);
            } else if (dotName.equals("org.lwjgl.input.Keyboard") ||
                       slashName.equals("org/lwjgl/input/Keyboard")) {
                System.out.println("[Ixeris] Patching Keyboard (" + transformedName + ")");
                return transformKeyboard(bytes);
            } else if (dotName.equals("org.lwjgl.opengl.Display") ||
                       slashName.equals("org/lwjgl/opengl/Display")) {
                System.out.println("[Ixeris] Patching Display (" + transformedName + ")");
                return transformDisplay(bytes);
            }
        } catch (Throwable t) {
            System.err.println("[Ixeris] Transform failed for " + transformedName + ": " + t);
            t.printStackTrace();
        }
        return bytes;
    }

    // ===================================================================
    //  ClassWriter with safe frame computation
    // ===================================================================

    private ClassWriter createClassWriter(ClassReader cr) {
        return new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (Throwable e) {
                    return "java/lang/Object";
                }
            }
        };
    }

    // ===================================================================
    //  Minecraft — inject thread split at head of run() / func_71411_J()
    // ===================================================================

    private byte[] transformMinecraft(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = createClassWriter(cr);

        cr.accept(new ClassVisitor(ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (mv != null && desc.equals("()V") && (name.equals("run") || name.equals("func_71411_J"))) {
                    System.out.println("[Ixeris] Transforming Minecraft." + name + "() for thread split.");
                    return new MethodVisitor(ASM5, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // if (Ixeris.beginThreadSplit(this)) return;
                            mv.visitVarInsn(ALOAD, 0);
                            mv.visitMethodInsn(INVOKESTATIC, IXERIS, "beginThreadSplit", "(Ljava/lang/Object;)Z", false);
                            Label skip = new Label();
                            mv.visitJumpInsn(IFEQ, skip);
                            mv.visitInsn(RETURN);
                            mv.visitLabel(skip);
                            mv.visitFrame(F_SAME, 0, null, 0, null);
                        }
                    };
                }
                return mv;
            }
        }, 0);

        return cw.toByteArray();
    }

    // ===================================================================
    //  Mouse — redirect input methods to InputManager on render thread
    // ===================================================================

    private byte[] transformMouse(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = createClassWriter(cr);

        cr.accept(new ClassVisitor(ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (mv == null) return null;

                if (name.equals("setGrabbed") && desc.equals("(Z)V")) {
                    return injectCacheUpdate(mv, "setMouseGrabbed", desc);
                }

                String mgrMethod = mapMouseMethod(name, desc);
                if (mgrMethod != null) {
                    return injectRedirect(mv, mgrMethod, desc);
                }
                return mv;
            }
        }, 0);

        return cw.toByteArray();
    }

    private String mapMouseMethod(String name, String desc) {
        if (desc.equals("()Z")) {
            if (name.equals("next"))              return "mouseNext";
            if (name.equals("getEventButtonState"))return "getMouseEventButtonState";
            if (name.equals("hasWheel"))           return "mouseHasWheel";
            if (name.equals("isGrabbed"))          return "isMouseGrabbed";
            if (name.equals("isInsideWindow"))     return "isMouseInsideWindow";
        }
        if (desc.equals("()I")) {
            if (name.equals("getEventButton"))     return "getMouseEventButton";
            if (name.equals("getEventDX"))         return "getMouseEventDX";
            if (name.equals("getEventDY"))         return "getMouseEventDY";
            if (name.equals("getEventX"))          return "getMouseEventX";
            if (name.equals("getEventY"))          return "getMouseEventY";
            if (name.equals("getEventDWheel"))     return "getMouseEventDWheel";
            if (name.equals("getX"))               return "getMouseX";
            if (name.equals("getY"))               return "getMouseY";
            if (name.equals("getDX"))              return "getMouseDX";
            if (name.equals("getDY"))              return "getMouseDY";
            if (name.equals("getDWheel"))          return "getMouseDWheel";
            if (name.equals("getButtonCount"))     return "getMouseButtonCount";
        }
        if (desc.equals("()J")) {
            if (name.equals("getEventNanoseconds"))return "getMouseEventNanoseconds";
        }
        if (desc.equals("(I)Z")) {
            if (name.equals("isButtonDown"))       return "isMouseButtonDown";
        }
        return null;
    }

    // ===================================================================
    //  Keyboard — redirect input methods to InputManager on render thread
    // ===================================================================

    private byte[] transformKeyboard(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = createClassWriter(cr);

        cr.accept(new ClassVisitor(ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (mv == null) return null;

                String mgrMethod = mapKeyboardMethod(name, desc);
                if (mgrMethod != null) {
                    return injectRedirect(mv, mgrMethod, desc);
                }
                return mv;
            }
        }, 0);

        return cw.toByteArray();
    }

    private String mapKeyboardMethod(String name, String desc) {
        if (desc.equals("()Z")) {
            if (name.equals("next"))               return "keyboardNext";
            if (name.equals("getEventKeyState"))   return "getKeyboardEventKeyState";
            if (name.equals("isRepeatEvent"))      return "isKeyboardRepeatEvent";
        }
        if (desc.equals("()I")) {
            if (name.equals("getEventKey"))        return "getKeyboardEventKey";
        }
        if (desc.equals("()C")) {
            if (name.equals("getEventCharacter"))  return "getKeyboardEventCharacter";
        }
        if (desc.equals("()J")) {
            if (name.equals("getEventNanoseconds"))return "getKeyboardEventNanoseconds";
        }
        if (desc.equals("(I)Z")) {
            if (name.equals("isKeyDown"))          return "isKeyDown";
        }
        return null;
    }

    // ===================================================================
    //  Display — skip processMessages, redirect isCloseRequested/wasResized
    // ===================================================================

    private byte[] transformDisplay(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = createClassWriter(cr);

        cr.accept(new ClassVisitor(ASM5, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (mv == null) return null;

                if (name.equals("processMessages") && desc.equals("()V")) {
                    return injectSkipOnRenderThread(mv);
                }
                if (name.equals("isCloseRequested") && desc.equals("()Z")) {
                    return injectRedirectField(mv, IXERIS, "closeRequested", "Z");
                }
                if (name.equals("wasResized") && desc.equals("()Z")) {
                    return injectRedirect(mv, "consumeResized", "()Z");
                }
                return mv;
            }
        }, 0);

        return cw.toByteArray();
    }

    // ===================================================================
    //  ASM injection helpers
    // ===================================================================

    /**
     * Emits the render-thread check at the current position.
     * If on the render thread, jumps to the redirect block.
     * Otherwise, falls through to the original code.
     *
     * @return the skip label (branch target for "not on render thread")
     */
    private Label emitRenderThreadCheck(MethodVisitor mv) {
        Label skip = new Label();
        mv.visitFieldInsn(GETSTATIC, IXERIS, "active", "Z");
        mv.visitJumpInsn(IFEQ, skip);
        mv.visitMethodInsn(INVOKESTATIC, THREAD, "currentThread", "()Ljava/lang/Thread;");
        mv.visitFieldInsn(GETSTATIC, IXERIS, "renderThread", "Ljava/lang/Thread;");
        mv.visitJumpInsn(IF_ACMPNE, skip);
        return skip;
    }

    /**
     * Full redirect: on the render thread, call InputManager.method() and return.
     * On other threads, fall through to original code.
     */
    private MethodVisitor injectRedirect(final MethodVisitor mv, final String mgrMethod, final String desc) {
        return new MethodVisitor(ASM5, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                Label skip = emitRenderThreadCheck(mv);
                // Load args (all target methods are static → locals start at 0)
                Type[] argTypes = Type.getArgumentTypes(desc);
                int local = 0;
                for (Type t : argTypes) {
                    mv.visitVarInsn(loadOp(t.getSort()), local);
                    local += t.getSize();
                }
                mv.visitMethodInsn(INVOKESTATIC, INPUT_MGR, mgrMethod, desc, false);
                mv.visitInsn(returnOp(Type.getReturnType(desc).getSort()));
                mv.visitLabel(skip);
                mv.visitFrame(F_SAME, 0, null, 0, null);
            }
        };
    }

    /**
     * Redirect to a static boolean field on render thread.
     */
    private MethodVisitor injectRedirectField(final MethodVisitor mv, final String owner, final String field, final String fieldDesc) {
        return new MethodVisitor(ASM5, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                Label skip = emitRenderThreadCheck(mv);
                mv.visitFieldInsn(GETSTATIC, owner, field, fieldDesc);
                mv.visitInsn(IRETURN);
                mv.visitLabel(skip);
                mv.visitFrame(F_SAME, 0, null, 0, null);
            }
        };
    }

    /**
     * Void return (skip) on render thread.
     */
    private MethodVisitor injectSkipOnRenderThread(final MethodVisitor mv) {
        return new MethodVisitor(ASM5, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                Label skip = emitRenderThreadCheck(mv);
                mv.visitInsn(RETURN);
                mv.visitLabel(skip);
                mv.visitFrame(F_SAME, 0, null, 0, null);
            }
        };
    }

    /**
     * Cache update without early return: update InputManager, then fall through
     * to original code (so native side-effects like grabMouse still happen).
     */
    private MethodVisitor injectCacheUpdate(final MethodVisitor mv, final String mgrMethod, final String desc) {
        return new MethodVisitor(ASM5, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                Label skip = new Label();
                mv.visitFieldInsn(GETSTATIC, IXERIS, "active", "Z");
                mv.visitJumpInsn(IFEQ, skip);
                Type[] argTypes = Type.getArgumentTypes(desc);
                int local = 0;
                for (Type t : argTypes) {
                    mv.visitVarInsn(loadOp(t.getSort()), local);
                    local += t.getSize();
                }
                mv.visitMethodInsn(INVOKESTATIC, INPUT_MGR, mgrMethod, desc, false);
                mv.visitLabel(skip);
                mv.visitFrame(F_SAME, 0, null, 0, null);
            }
        };
    }

    // ===================================================================
    //  Opcode helpers
    // ===================================================================

    private static int loadOp(int sort) {
        switch (sort) {
            case Type.BOOLEAN: case Type.BYTE: case Type.CHAR:
            case Type.SHORT:  case Type.INT:    return ILOAD;
            case Type.LONG:    return LLOAD;
            case Type.FLOAT:   return FLOAD;
            case Type.DOUBLE:  return DLOAD;
            default:           return ALOAD;
        }
    }

    private static int returnOp(int sort) {
        switch (sort) {
            case Type.VOID:    return RETURN;
            case Type.BOOLEAN: case Type.BYTE: case Type.CHAR:
            case Type.SHORT:   case Type.INT:  return IRETURN;
            case Type.LONG:    return LRETURN;
            case Type.FLOAT:   return FRETURN;
            case Type.DOUBLE:  return DRETURN;
            default:           return ARETURN;
        }
    }
}
