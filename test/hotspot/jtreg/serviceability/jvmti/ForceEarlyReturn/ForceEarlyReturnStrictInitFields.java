/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @summary Test ForceEarlyReturnVoid when the target thread's top frame is the class
 *     initializer, constructor or method of a class with strictly-initialized fields.
 * @enablePreview
 * @library /test/lib
 * @build ${test.main.class}
 * @run driver jdk.test.lib.helpers.StrictProcessor
 *     ForceEarlyReturnStrictInitFields$BeforeAndAfterSuper1$TestClass
 *     ForceEarlyReturnStrictInitFields$BeforeAndAfterSuper2$SuperClass
 *     ForceEarlyReturnStrictInitFields$BeforeAndAfterSuper3$TestClass
 *     ForceEarlyReturnStrictInitFields$BeforeAndAfterThis$TestClass
 *     ForceEarlyReturnStrictInitFields$RedefineConstructor$TestClass
 *     ForceEarlyReturnStrictInitFields$MethodAfterSuper$TestClass
 *     ForceEarlyReturnStrictInitFields$MethodAfterInit$TestClass
 *     ForceEarlyReturnStrictInitFields$ClassInitializerBeforeSet$TestClass
 *     ForceEarlyReturnStrictInitFields$ClassInitializerAfterSet$TestClass
 * @run junit/othervm/native --enable-native-access=ALL-UNNAMED -agentlib:ForceEarlyReturnStrictInitFields ${test.main.class}
 */

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.io.InputStream;
import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

import jdk.test.lib.helpers.StrictInit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

class ForceEarlyReturnStrictInitFields {

    private static final int JVMTI_ERROR_NONE = 0;
    private static final int JVMTI_ERROR_OPAQUE_FRAME = 32;

    /**
     * Base class to test ForceEarlyReturnVoid.
     */
    abstract class ForceEarlyReturnTest {
        volatile boolean ready;
        volatile boolean canContinue;
        volatile Throwable exception;

        /**
         * Starts a thread to execute the given action. The action is expected to spin
         * at the given class/method. Once spinning, the thread is suspsended and
         * ForceEarlyReturnVoid is invoked to attempt to force it to return early.
         * @return the return from ForceEarlyReturnVoid
         */
        int test(Executable action, Class<?> targetClass, String targetMethod) throws Exception {
            assertFalse(ready, "Test already executed");
            Thread thread = Thread.ofPlatform().start(() -> {
                try {
                    action.execute();
                } catch (Throwable ex) {
                    exception = ex;
                }
            });
            int err;
            boolean suspended = false;
            try {
                // wait for target thread to spin
                while (!ready) {
                    Thread.sleep(10);
                }
                assertEquals(JVMTI_ERROR_NONE, suspendThread(thread));
                suspended = true;
                assertTopFrame(thread, targetClass, targetMethod);
                err = forceEarlyReturnVoid(thread);
            } finally {
                canContinue = true;
                if (suspended) resumeThread(thread);
                thread.join();
            }
            assertNull(exception, "target thread threw exception");
            return err;
        }
    }

    /**
     * Test ForceEarlyReturnVoid when the target thread's top frame is the
     * constructor of a class with a strictly-initialized instance field.
     */
    @Nested
    class BeforeAndAfterSuper1 extends ForceEarlyReturnTest {
        class TestClass {
            @StrictInit
            private int x;

            TestClass(ForceEarlyReturnTest test, int where) {
                x = 100;

                // before super
                if (where == 1) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }

                super();

                // after super
                if (where == 2) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }
            }
        }

        @Test
        void testBeforeSuper() throws Exception {
            int err = test(() -> new TestClass(this, 1), TestClass.class, "<init>");
            assertEquals(JVMTI_ERROR_OPAQUE_FRAME, err);
        }

        @Test
        void testAfterSuper() throws Exception {
            int err = test(() -> new TestClass(this, 2), TestClass.class, "<init>");
            assertEquals(JVMTI_ERROR_NONE, err);
        }
    }

    /**
     * Test ForceEarlyReturnVoid when the target thread's top frame is the
     * constructor or super-constructor of a class with a strictly-initialized
     * instance field. The strict field is declared in the super class.
     */
    @Nested
    class BeforeAndAfterSuper2 extends ForceEarlyReturnTest {
        class SuperClass {
            @StrictInit
            private int x;

            SuperClass(ForceEarlyReturnTest test, int where) {
                x = 100;

                // before super
                if (where == 1) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }

                super();

                // after super
                if (where == 2) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }
            }
        }

        class TestClass extends SuperClass {
            TestClass(ForceEarlyReturnTest test, int where) {
                // before super
                if (where == 3) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }

                super(test, where);

                // after super
                if (where == 4) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }
            }
        }

        @Test
        void testBeforeSuperSuper() throws Exception {
            int err = test(() -> new TestClass(this, 1), SuperClass.class, "<init>");
            assertEquals(JVMTI_ERROR_OPAQUE_FRAME, err);
        }

        @Test
        void testAfterSuperSuper() throws Exception {
            int err = test(() -> new TestClass(this, 2), SuperClass.class, "<init>");
            assertEquals(JVMTI_ERROR_NONE, err);
        }

        @Test
        void testBeforeSuper() throws Exception {
            int err = test(() -> new TestClass(this, 3), TestClass.class, "<init>");
            assertEquals(JVMTI_ERROR_OPAQUE_FRAME, err);
        }

        @Test
        void testAfterSuper() throws Exception {
            int err = test(() -> new TestClass(this, 4), TestClass.class, "<init>");
            assertEquals(JVMTI_ERROR_NONE, err);
        }
    }

    /**
     * Test ForceEarlyReturnVoid when the target thread's top frame is the
     * constructor or super-constructor of a class with a strictly-initialized
     * instance field. The strict field is declared in the sub class.
     */
    @Nested
    class BeforeAndAfterSuper3 extends ForceEarlyReturnTest {
        class SuperClass {
            SuperClass(ForceEarlyReturnTest test, int where) {
                // before super
                if (where == 1) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }

                super();

                // after super
                if (where == 2) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }
            }
        }

        class TestClass extends SuperClass {
            @StrictInit
            private int x;

            TestClass(ForceEarlyReturnTest test, int where) {
                x = 100;

                // before super
                if (where == 3) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }

                super(test, where);

                // after super
                if (where == 4) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }
            }
        }

        @Test
        void testBeforeSuperSuper() throws Exception {
            int err = test(() -> new TestClass(this, 1), SuperClass.class, "<init>");
            assertEquals(JVMTI_ERROR_NONE, err);
        }

        @Test
        void testAfterSuperSuper() throws Exception {
            int err = test(() -> new TestClass(this, 2), SuperClass.class, "<init>");
            assertEquals(JVMTI_ERROR_NONE, err);
        }

        @Test
        void testBeforeSuper() throws Exception {
            int err = test(() -> new TestClass(this, 3), TestClass.class, "<init>");
            assertEquals(JVMTI_ERROR_OPAQUE_FRAME, err);
        }

        @Test
        void testAfterSuper() throws Exception {
            int err = test(() -> new TestClass(this, 4), TestClass.class, "<init>");
            assertEquals(JVMTI_ERROR_NONE, err);
        }
    }

    /**
     * Test ForceEarlyReturnVoid when the target thread's top frame is the
     * constructor of a class with a strictly-initialized instance field
     * before it chains to the another constructor to set the field.
     */
    @Nested
    class BeforeAndAfterThis extends ForceEarlyReturnTest {
        class TestClass {
            @StrictInit
            private int x;

            TestClass() {
                x = 100;
                super();
            }

            TestClass(ForceEarlyReturnTest test, int where) {
                // before this
                if (where == 1) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }

                this();

                // after this
                if (where == 2) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }
            }
        }

        @Test
        void testBeforeThis() throws Exception {
            int err = test(() -> new TestClass(this, 1), TestClass.class, "<init>");
            assertEquals(JVMTI_ERROR_OPAQUE_FRAME, err);
        }

        @Test
        void testAfterThis() throws Exception {
            int err = test(() -> new TestClass(this, 2), TestClass.class, "<init>");
            assertEquals(JVMTI_ERROR_NONE, err);
        }
    }

    /**
     * Test ForceEarlyReturnVoid when the target thread's top frame is the
     * constructor of a class with a strictly-initialized instance field and
     * control flow in the constructor. This is a class only shape.
     */
    @Nested
    class ConstructorControlFlow extends ForceEarlyReturnTest {

        @Test
        void testBeforeSuperLeft() throws Exception {
            int err = test(1);
            assertEquals(JVMTI_ERROR_OPAQUE_FRAME, err);
        }

        @Test
        void testAfterSuperLeft() throws Exception {
            int err = test(2);
            assertEquals(JVMTI_ERROR_NONE, err);
        }

        @Test
        void testBeforeSuperRight() throws Exception {
            int err = test(3);
            assertEquals(JVMTI_ERROR_OPAQUE_FRAME, err);
        }

        @Test
        void testAfterSuperRight() throws Exception {
            int err = test(4);
            assertEquals(JVMTI_ERROR_NONE, err);
        }

        /**
         * Generate a class with control flow.
         */
        int test(int where) throws Exception {
            byte[] classBytes = generateTestClass(getClass().getName() + where);
            Class<?> testClass = MethodHandles.lookup().defineClass(classBytes);
            Constructor ctor = testClass.getDeclaredConstructor(ForceEarlyReturnTest.class, int.class);
            return test(() -> ctor.newInstance(this, where), testClass, "<init>");
        }

        /**
         * class $TestClass$ {
         *     @StrictInit int x;
         *
         *     $TestClass$(ForceEarlyReturnTest test, int where) {
         *         x = 100;
         *         if (where <= 2) {
         *             if (where == 1) {
         *                 test.ready = true;
         *                 while (!test.canContinue) {}
         *             }
         *             super();
         *             if (where == 2) {
         *                 test.ready = true;
         *                 while (!test.canContinue) {}
         *             }
         *         } else {
         *             if (where == 3) {
         *                 test.ready = true;
         *                 while (!test.canContinue) {}
         *             }
         *             super();
         *             if (where == 4) {
         *                 test.ready = true;
         *                 while (!test.canContinue) {}
         *             }
         *         }
         *     }
         * }
         */
        byte[] generateTestClass(String className) {
            ClassDesc testClass = ClassDesc.of(className);
            ClassDesc testType = ClassDesc.of(ForceEarlyReturnTest.class.getName());
            MethodTypeDesc ctorType = MethodTypeDesc.of(CD_void, testType, CD_int);
            return ClassFile.of().build(testClass, clb -> clb
                    .withVersion(latestMajorVersion(), PREVIEW_MINOR_VERSION)
                    .withFlags(ACC_IDENTITY)
                    .withField("x", CD_int, ACC_STRICT_INIT)
                    .withMethodBody(INIT_NAME, ctorType, 0, cob -> {
                        Label elseBranch = cob.newLabel();
                        Label afterWait1 = cob.newLabel();
                        Label afterWait3 = cob.newLabel();
                        Label end = cob.newLabel();
                        cob.aload(0)
                                .bipush(100)
                                .putfield(testClass, "x", CD_int)
                                .iload(2) // where
                                .iconst_2()
                                .if_icmpgt(elseBranch)  // where > 2
                                .iload(2)
                                .iconst_1()
                                .if_icmpne(afterWait1);
                        Label wait1 = cob.newLabel();
                        cob.aload(1)
                                .iconst_1()
                                .putfield(testType, "ready", CD_boolean)
                                .labelBinding(wait1)
                                .aload(1)
                                .getfield(testType, "canContinue", CD_boolean)
                                .ifeq(wait1)
                                .labelBinding(afterWait1)
                                .aload(0)
                                .invokespecial(CD_Object, INIT_NAME, MTD_void)
                                .iload(2)
                                .iconst_2()
                                .if_icmpne(end);
                        Label wait2 = cob.newLabel();
                        cob.aload(1)
                                .iconst_1()
                                .putfield(testType, "ready", CD_boolean)
                                .labelBinding(wait2)
                                .aload(1)
                                .getfield(testType, "canContinue", CD_boolean)
                                .ifeq(wait2)
                                .goto_(end)
                                .labelBinding(elseBranch)
                                .iload(2)
                                .iconst_3()
                                .if_icmpne(afterWait3);
                        Label wait3 = cob.newLabel();
                        cob.aload(1)
                                .iconst_1()
                                .putfield(testType, "ready", CD_boolean)
                                .labelBinding(wait3)
                                .aload(1)
                                .getfield(testType, "canContinue", CD_boolean)
                                .ifeq(wait3)
                                .labelBinding(afterWait3)
                                .aload(0)
                                .invokespecial(CD_Object, INIT_NAME, MTD_void)
                                .aload(1)
                                .iconst_1()
                                .putfield(testType, "ready", CD_boolean)
                                .iload(2)
                                .iconst_4()
                                .if_icmpne(end);
                        Label wait4 = cob.newLabel();
                        cob.aload(1)
                                .iconst_1()
                                .putfield(testType, "ready", CD_boolean)
                                .labelBinding(wait4)
                                .aload(1)
                                .getfield(testType, "canContinue", CD_boolean)
                                .ifeq(wait4)
                                .labelBinding(end)
                                .return_();
                    }));
        }
    }

    /**
     * Test ForceEarlyReturnVoid when the target thread's top frame is the
     * constructor of a class defined to the bootstrap class loader with a
     * strictly-initialized instance field.
     */
    @Nested
    class ConstructorNotVerified {

        @Test
        void testBeforeSuper() throws Exception {
            int err = test(1);
            assertEquals(JVMTI_ERROR_OPAQUE_FRAME, err);
        }

        @Test
        void testAfterSuper() throws Exception {
            int err = test(2);
            // JVMTI_ERROR_OPAQUE_FRAME expected as class is not verified
            assertEquals(JVMTI_ERROR_OPAQUE_FRAME, err);
        }

        /**
         * Generate a class defined to the boot class loader.
         * Test ForceEarlyReturnVoid before or after super.
         */
        int test(int where) throws Exception {
            String className = getClass().getName() + where;
            Class<?> testClass = defineClass(className, generateTestClass(className), null);
            assertNull(testClass.getClassLoader(), "class should be defined by boot loader");

            // used to access fields in the generated class defined to boot loader
            Field ready = testClass.getField("ready");
            Field canContinue = testClass.getField("canContinue");
            Field exception = testClass.getField("exception");
            Constructor<?> ctor = testClass.getConstructor(int.class);

            Thread thread = Thread.ofPlatform().start(() -> {
                try {
                    ctor.newInstance(where);
                } catch (Throwable ex) {
                    try {
                        exception.set(null, ex);
                    } catch (IllegalAccessException _) {}
                }
            });
            int err;
            boolean suspended = false;
            try {
                while (!ready.getBoolean(null)) {
                    Thread.sleep(10);
                }
                assertEquals(JVMTI_ERROR_NONE, suspendThread(thread));
                suspended = true;
                assertTopFrame(thread, testClass, "<init>");
                err = forceEarlyReturnVoid(thread);
            } finally {
                canContinue.setBoolean(null, true);
                if (suspended) resumeThread(thread);
                thread.join();
            }
            assertNull(exception.get(null), "target thread threw exception");
            return err;
        }

        /**
         * public class $TestClass$ {
         *     public static volatile boolean ready;
         *     public static volatile boolean canContinue;
         *     public static volatile Throwable exception;
         *
         *     @StrictInit int x;
         *
         *     public $TestClass$(int where) {
         *         x = 100;
         *         if (where == 1) {
         *             ready = true;
         *             while (!canContinue) {}
         *         }
         *         super();
         *         if (where == 2) {
         *             ready = true;
         *             while (!canContinue) {}
         *         }
         *     }
         * }
         */
        byte[] generateTestClass(String className) {
            ClassDesc testClass = ClassDesc.of(className);
            return ClassFile.of().build(testClass, clb -> clb
                    .withVersion(latestMajorVersion(), PREVIEW_MINOR_VERSION)
                    .withFlags(ACC_PUBLIC | ACC_IDENTITY)
                    .withField("ready", CD_boolean, ACC_PUBLIC | ACC_STATIC | ACC_VOLATILE)
                    .withField("canContinue", CD_boolean, ACC_PUBLIC | ACC_STATIC | ACC_VOLATILE)
                    .withField("exception", ClassDesc.of("java.lang.Throwable"),
                            ACC_PUBLIC | ACC_STATIC | ACC_VOLATILE)
                    .withField("x", CD_int, ACC_STRICT_INIT)
                    .withMethodBody(INIT_NAME, MethodTypeDesc.of(CD_void, CD_int), ACC_PUBLIC, cob -> {
                        Label wait1 = cob.newLabel();
                        Label wait2 = cob.newLabel();
                        cob.aload(0)
                                .bipush(100)
                                .putfield(testClass, "x", CD_int)
                                .iload(1)
                                .iconst_1()
                                .if_icmpne(wait1);
                        Label beforeWait = cob.newLabel();
                        cob.iconst_1()
                                .putstatic(testClass, "ready", CD_boolean)
                                .labelBinding(beforeWait)
                                .getstatic(testClass, "canContinue", CD_boolean)
                                .ifeq(beforeWait)
                                .labelBinding(wait1)
                                .aload(0)
                                .invokespecial(CD_Object, INIT_NAME, MTD_void)
                                .iload(1)
                                .iconst_2()
                                .if_icmpne(wait2);
                        Label afterWait = cob.newLabel();
                        cob.iconst_1()
                                .putstatic(testClass, "ready", CD_boolean)
                                .labelBinding(afterWait)
                                .getstatic(testClass, "canContinue", CD_boolean)
                                .ifeq(afterWait)
                                .labelBinding(wait2)
                                .return_();
                    }));
        }
    }

    /**
     * Test ForceEarlyReturnVoid with RedefineClasses. The target thread's top frame
     * is constructor of a class with a strictly-initialized instance field. The
     * constructor is redefined to wait before or after super.
     */
    @Nested
    class RedefineConstructor extends ForceEarlyReturnTest {
        static class TestClass {
            @StrictInit
            private int ver;

            TestClass(ForceEarlyReturnTest test) {
                ver = 1;
                super();
            }

            static void assertVersion(int expected) {
                var testObj = new TestClass(null);
                assertEquals(expected, testObj.ver);
            }
        }

        @Test
        void testBeforeSuper() throws Exception {
            TestClass.assertVersion(1);
            byte[] classBytes = classBytes(TestClass.class);

            // redefine to add wait before super
            int err = redefineClass(TestClass.class, addWaitBeforeSuper(classBytes));
            assertEquals(JVMTI_ERROR_NONE, err);
            try {
                // check TestClass is version 2
                TestClass.assertVersion(2);
                err = test(() -> new TestClass(this), TestClass.class, "<init>");
                assertEquals(JVMTI_ERROR_OPAQUE_FRAME, err);
            } finally {
                err = redefineClass(TestClass.class, classBytes);  // restore
                assertEquals(JVMTI_ERROR_NONE, err);
            }
        }

        @Test
        void testAfterSuper() throws Exception {
            TestClass.assertVersion(1);
            byte[] classBytes = classBytes(TestClass.class);

            // redefine to add wait after super
            int err = redefineClass(TestClass.class, addWaitAfterSuper(classBytes));
            assertEquals(JVMTI_ERROR_NONE, err);
            try {
                // check TestClass is version 3
                TestClass.assertVersion(3);
                err = test(() -> new TestClass(this), TestClass.class, "<init>");
                assertEquals(JVMTI_ERROR_NONE, err);
            } finally {
                err = redefineClass(TestClass.class, classBytes);  // restore
                assertEquals(JVMTI_ERROR_NONE, err);
            }
        }

        /**
         * Transforms the constructor to:
         *
         *     TestClass(ForceEarlyReturnTest test) {
         *         ver = 2;
         *         if (test != null) {
         *             test.ready = true;
         *             while (!test.canContinue()) {}
         *         }
         *         super();
         *     }
         *
         */
        byte[] addWaitBeforeSuper(byte[] classBytes) {
            ClassDesc testType = ClassDesc.of(ForceEarlyReturnTest.class.getName());
            CodeTransform addWait = (cob, element) -> {
                // ver 1 -> 2
                if (element instanceof ConstantInstruction constant
                        && constant.constantValue().equals(1)) {
                    cob.bipush(2);
                    return;
                }
                if (element instanceof InvokeInstruction invoke
                        && invoke.opcode() == Opcode.INVOKESPECIAL
                        && invoke.owner().asSymbol().equals(CD_Object)
                        && invoke.name().equalsString(INIT_NAME)) {
                    Label skipWait = cob.newLabel();
                    Label wait = cob.newLabel();
                    cob.aload(1)
                            .ifnull(skipWait)
                            .aload(1)
                            .iconst_1()
                            .putfield(testType, "ready", CD_boolean)
                            .labelBinding(wait)
                            .aload(1)
                            .getfield(testType, "canContinue", CD_boolean)
                            .ifeq(wait)
                            .labelBinding(skipWait);
                }
                cob.with(element);
            };
            ClassFile classFile = ClassFile.of();
            return ClassFile.of().transformClass(classFile.parse(classBytes),
                    ClassTransform.transformingMethodBodies(
                            method -> method.methodName().equalsString(INIT_NAME), addWait));
        }

        /**
         * Transforms the constructor to:
         *
         *     TestClass(ForceEarlyReturnTest test) {
         *         ver = 3;
         *         super();
         *         if (test != null) {
         *             test.ready = true;
         *             while (!test.canContinue()) {}
         *         }
         *     }
         *
         */
        byte[] addWaitAfterSuper(byte[] classBytes) {
            ClassDesc testType = ClassDesc.of(ForceEarlyReturnTest.class.getName());
            CodeTransform addWait = (cob, element) -> {
                // ver 1 -> 3
                if (element instanceof ConstantInstruction constant
                        && constant.constantValue().equals(1)) {
                    cob.sipush(3);
                    return;
                }
                if (element instanceof InvokeInstruction invoke
                        && invoke.opcode() == Opcode.INVOKESPECIAL
                        && invoke.owner().asSymbol().equals(CD_Object)
                        && invoke.name().equalsString(INIT_NAME)) {
                    cob.with(element);
                    Label skipWait = cob.newLabel();
                    Label wait = cob.newLabel();
                    cob.aload(1)
                            .ifnull(skipWait)
                            .aload(1)
                            .iconst_1()
                            .putfield(testType, "ready", CD_boolean)
                            .labelBinding(wait)
                            .aload(1)
                            .getfield(testType, "canContinue", CD_boolean)
                            .ifeq(wait)
                            .labelBinding(skipWait);
                    return;
                }
                cob.with(element);
            };
            ClassFile classFile = ClassFile.of();
            return classFile.transformClass(classFile.parse(classBytes),
                    ClassTransform.transformingMethodBodies(
                            method -> method.methodName().equalsString(INIT_NAME), addWait));
        }
    }

    /**
     * Test ForceEarlyReturnVoid when the target thread's top frame is a method
     * invoked by the constructor of a class with a strictly-initialized instance
     * field after super() is invoked. This test exercises the implementation for the
     * case that the caller of the method that returns early is a constructor of a
     * class with strictly-initialized fields.
     */
    @Nested
    class MethodAfterSuper extends ForceEarlyReturnTest {
        static volatile boolean postInitFinished;
        static volatile boolean initFinished;

        class TestClass {
            @StrictInit
            private int x;

            TestClass(MethodAfterSuper test) {
                x = 100;
                super();
                postInit(test);
                initFinished = true;
            }

            void postInit(MethodAfterSuper test) {
                // spin here until ForceEarlyReturnVoid executes
                test.ready = true;
                while (!test.canContinue) {}

                // should not get here
                postInitFinished = true;
            }
        }

        @Test
        void test() throws Exception {
            int err = test(() -> new TestClass(this), TestClass.class, "postInit");
            assertEquals(JVMTI_ERROR_NONE, err);
            assertFalse(postInitFinished, "postInit method should have returned early");
            assertTrue(initFinished, "<init> did not finish");
        }
    }

    /**
     * Test ForceEarlyReturnVoid when the target thread's top frame is a method of a
     * class with a strictly-initialized instance field. This test ensures that force
     * early is allowed when the top-frame is a method  of a classes with
     * strictly-initialized fields.
     */
    @Nested
    class MethodAfterInit extends ForceEarlyReturnTest {
        static volatile boolean runFinished;

        class TestClass {
            @StrictInit
            private int x;

            TestClass() {
                x = 100;
                super();
            }

            void run(MethodAfterInit test) {
                // spin here until ForceEarlyReturnVoid executes
                test.ready = true;
                while (!test.canContinue) {}

                // should not get here
                runFinished = true;
            }
        }

        @Test
        void test() throws Exception {
            var obj = new TestClass();
            int err = test(() -> obj.run(this), TestClass.class, "run");
            assertEquals(JVMTI_ERROR_NONE, err);
            assertFalse(runFinished, "run method should have returned early");
        }
    }

    /**
     * Test ForceEarlyReturnVoid when the target thread's top frame is the class
     * initializer of a class with a strictly-initialized static field before
     * the field is set.
     */
    @Nested
    class ClassInitializerBeforeSet {
        static volatile boolean ready;
        static volatile boolean canContinue;
        static volatile boolean finished;
        static volatile Throwable exception;

        class TestClass {
            @StrictInit
            private static int x;

            static {
                // spin here until ForceEarlyReturnVoid executes
                ready = true;
                while (!canContinue) {}

                // should not get there
                x = 100;
                finished = true;
            }
        }

        @Test
        void test() throws Exception {
            Thread thread = Thread.ofPlatform().start(() -> {
                try {
                    MethodHandles.lookup().ensureInitialized(TestClass.class);
                } catch (Throwable ex) {
                    // ExceptionInInitializerError expected
                    exception = ex;
                }
            });
            boolean suspended = false;
            try {
                // wait for target thread to spin in class initializer
                while (!ready) {
                    Thread.sleep(10);
                }
                assertEquals(JVMTI_ERROR_NONE, suspendThread(thread));
                suspended = true;
                assertTopFrame(thread, TestClass.class, "<clinit>");
                assertEquals(JVMTI_ERROR_NONE, forceEarlyReturnVoid(thread));
            } finally {
                canContinue = true;
                if (suspended) resumeThread(thread);
                thread.join();
            }
            assertFalse(finished, "<clinit> should have returned early");

            // check that ExceptionInInitializerError was thrown
            Throwable ex = exception;
            assertInstanceOf(ExceptionInInitializerError.class, ex);
            assertInstanceOf(IllegalStateException.class, ex.getCause());

            // class should be in error
            assertThrows(NoClassDefFoundError.class, () -> { var _ = TestClass.x; });
        }
    }

    /**
     * Test ForceEarlyReturnVoid when the target thread's top frame is the class
     * initializer of a class with a strictly-initialized static field after
     * the field is set.
     */
    @Nested
    class ClassInitializerAfterSet {
        static volatile boolean ready;
        static volatile boolean canContinue;
        static volatile boolean finished;
        static volatile Throwable exception;

        class TestClass {
            @StrictInit
            private static int x;

            static {
                x = 100;

                // spin here until ForceEarlyReturnVoid executes
                ready = true;
                while (!canContinue) {}

                // should not get there
                finished = true;
            }
        }

        @Test
        void test() throws Exception {
            Thread thread = Thread.ofPlatform().start(() -> {
                try {
                    MethodHandles.lookup().ensureInitialized(TestClass.class);
                } catch (Throwable ex) {
                    // no exception is expected
                    exception = ex;
                }
            });
            boolean suspended = false;
            try {
                // wait for target thread to spin in class initializer
                while (!ready) {
                    Thread.sleep(10);
                }
                assertEquals(JVMTI_ERROR_NONE, suspendThread(thread));
                suspended = true;
                assertTopFrame(thread, TestClass.class, "<clinit>");
                assertEquals(JVMTI_ERROR_NONE, forceEarlyReturnVoid(thread));
            } finally {
                canContinue = true;
                if (suspended) resumeThread(thread);
                thread.join();
            }
            assertFalse(finished, "<clinit> should have returned early");
            assertNull(exception, "no exception expected");
            assertEquals(100, TestClass.x);
        }
    }

    /**
     * Test ForceEarlyReturnVoid when the target thread's top frame is the
     * constructor of a value class with an instance field.
     */
    @Nested
    class ValueClassTest extends ForceEarlyReturnTest {
        value class TestClass {
            private int x;

            TestClass(ForceEarlyReturnTest test, int where) {
                x = 100;

                // before super
                if (where == 1) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }

                super();

                // after super
                if (where == 2) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }
            }
        }

        @Test
        void testBeforeSuper() throws Exception {
            int err = test(() -> new TestClass(this, 1), TestClass.class, "<init>");
            assertEquals(JVMTI_ERROR_OPAQUE_FRAME, err);
        }

        @Test
        void testAfterSuper() throws Exception {
            int err = test(() -> new TestClass(this, 2), TestClass.class, "<init>");
            assertEquals(JVMTI_ERROR_NONE, err);
        }
    }

    /**
     * Test ForceEarlyReturnVoid when the target thread's top frame is the
     * constructor of a record with an instance field.
     */
    @Nested
    class RecordTest extends ForceEarlyReturnTest {
        record TestClass(int x) {
            TestClass(ForceEarlyReturnTest test, int where, int x) {
                // before this
                if (where == 1) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }

                this(x);

                // after this
                if (where == 2) {
                    test.ready = true;
                    while (!test.canContinue) {}
                }
            }
        }

        @Test
        void testBeforeThis() throws Exception {
            int err = test(() -> new TestClass(this, 1, 100), TestClass.class, "<init>");
            assertEquals(JVMTI_ERROR_OPAQUE_FRAME, err);
        }

        @Test
        void testAfterThis() throws Exception {
            int err = test(() -> new TestClass(this, 2, 100), TestClass.class, "<init>");
            assertEquals(JVMTI_ERROR_NONE, err);
        }
    }


    /**
     * Asserts that the given thread's top frame is the expected class/method.
     */
    static void assertTopFrame(Thread thread, Class<?> clazz, String methodName) {
        StackTraceElement[] stack = thread.getStackTrace();
        assertTrue(stack.length > 0);
        assertEquals(clazz.getName(), stack[0].getClassName());
        assertEquals(methodName, stack[0].getMethodName());
    }

    /**
     * Returns the class bytes for the given class.
     */
    static byte[] classBytes(Class<?> clazz) throws Exception {
        byte[] classBytes;
        String rn = "/" + clazz.getName().replace('.', '/') + ".class";
        InputStream in = clazz.getResourceAsStream(rn);
        assertNotNull(in);
        try (in) {
            return in.readAllBytes();
        }
    }

    static native int suspendThread(Thread thread);
    static native int resumeThread(Thread thread);
    static native int forceEarlyReturnVoid(Thread thread);
    static native Class<?> defineClass(String name, byte[] bytes, ClassLoader loader);
    static native int redefineClass(Class<?> clazz, byte[] bytes);
}
