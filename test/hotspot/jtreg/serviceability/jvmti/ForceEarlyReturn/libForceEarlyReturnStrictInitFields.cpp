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

#include <string.h>
#include "jni.h"
#include "jvmti.h"

static jvmtiEnv *jvmti;

extern "C" {

extern JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  jvmtiError err;
  jvmtiCapabilities caps;

  if (jvm->GetEnv((void **) &jvmti, JVMTI_VERSION) != JNI_OK) {
    return JNI_ERR;
  }

  memset(&caps, 0, sizeof(caps));
  caps.can_suspend = 1;
  caps.can_force_early_return = 1;
  caps.can_redefine_classes = 1;
  err = jvmti->AddCapabilities( &caps);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  return JNI_OK;
}

JNIEXPORT jint Java_ForceEarlyReturnStrictInitFields_suspendThread(JNIEnv *env, jclass ignore, jthread target) {
  return (jint) jvmti->SuspendThread(target);
}

JNIEXPORT jint Java_ForceEarlyReturnStrictInitFields_resumeThread(JNIEnv *env, jclass ignore, jthread target) {
  return (jint) jvmti->ResumeThread(target);
}

JNIEXPORT jint Java_ForceEarlyReturnStrictInitFields_forceEarlyReturnVoid(JNIEnv *env, jclass ignore, jthread target) {
  return (jint) jvmti->ForceEarlyReturnVoid(target);
}

JNIEXPORT jclass JNICALL
Java_ForceEarlyReturnStrictInitFields_defineClass(JNIEnv* env, jclass ignore, jstring name, jbyteArray bytes, jobject loader) {
  const char* class_name = env->GetStringUTFChars(name, nullptr);
  if (class_name == nullptr) {
    return nullptr;
  }
  jbyte* class_bytes = env->GetByteArrayElements(bytes, nullptr);
  if (class_bytes == nullptr) {
    env->ReleaseStringUTFChars(name, class_name);
    return nullptr;
  }
  jclass klass = env->DefineClass(class_name, loader, class_bytes, env->GetArrayLength(bytes));
  env->ReleaseByteArrayElements(bytes, class_bytes, JNI_ABORT);
  env->ReleaseStringUTFChars(name, class_name);
  return klass;
}

JNIEXPORT jint JNICALL
Java_ForceEarlyReturnStrictInitFields_redefineClass(JNIEnv* env, jclass ignore, jclass klass, jbyteArray bytes) {
  jbyte* class_bytes = env->GetByteArrayElements(bytes, nullptr);
  if (class_bytes == nullptr) {
    return JVMTI_ERROR_OUT_OF_MEMORY;
  }
  jvmtiClassDefinition class_def = {
    klass,
    env->GetArrayLength(bytes),
    reinterpret_cast<unsigned char*>(class_bytes)
  };
  jvmtiError err = jvmti->RedefineClasses(1, &class_def);
  env->ReleaseByteArrayElements(bytes, class_bytes, JNI_ABORT);
  return err;
}

}
