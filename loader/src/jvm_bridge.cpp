// language: C++20, target: Windows 11
// finds the JVM inside the Minecraft process, loads agent bytecode from memory via JNI
// uses a custom URLClassLoader so the classes can be fully GC'd on unhook

#include "jvm_bridge.h"
#include <windows.h>
#include <jni.h>

// JNI_GetCreatedJavaVMs signature
typedef jint(JNICALL* fn_JNI_GetCreatedJavaVMs)(JavaVM**, jsize, jsize*);

static JavaVM* find_jvm() {
    // jvm.dll is already loaded in the Minecraft process
    HMODULE jvm_dll = GetModuleHandleA("jvm.dll");
    if (!jvm_dll) return nullptr;

    auto get_vms = reinterpret_cast<fn_JNI_GetCreatedJavaVMs>(
        GetProcAddress(jvm_dll, "JNI_GetCreatedJavaVMs")
    );
    if (!get_vms) return nullptr;

    JavaVM* vm = nullptr;
    jsize count = 0;
    if (get_vms(&vm, 1, &count) != JNI_OK || count == 0) return nullptr;

    return vm;
}

bool jvm_load_agent(const uint8_t* jar_bytes, int jar_length) {
    JavaVM* vm = find_jvm();
    if (!vm) return false;

    JNIEnv* env = nullptr;
    bool did_attach = false;

    // try to get existing env (if we're on a JVM thread)
    jint rc = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
    if (rc == JNI_EDETACHED) {
        // attach this native thread to the JVM
        JavaVMAttachArgs args{};
        args.version = JNI_VERSION_1_8;
        args.name = nullptr;
        args.group = nullptr;
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), &args) != JNI_OK)
            return false;
        did_attach = true;
    } else if (rc != JNI_OK) {
        return false;
    }

    bool success = false;

    // strategy: write jar bytes to a ByteArrayInputStream, create a JarInputStream,
    // read entries, defineClass each .class entry via a custom classloader.
    // this avoids touching the filesystem entirely.

    // step 1: create byte[] from our native buffer
    jbyteArray byte_array = env->NewByteArray(jar_length);
    if (!byte_array) goto cleanup;
    env->SetByteArrayRegion(byte_array, 0, jar_length,
                            reinterpret_cast<const jbyte*>(jar_bytes));

    {
        // step 2: ByteArrayInputStream from the byte[]
        jclass bais_class = env->FindClass("java/io/ByteArrayInputStream");
        jmethodID bais_ctor = env->GetMethodID(bais_class, "<init>", "([B)V");
        jobject bais = env->NewObject(bais_class, bais_ctor, byte_array);

        // step 3: JarInputStream to parse jar entries
        jclass jis_class = env->FindClass("java/util/jar/JarInputStream");
        jmethodID jis_ctor = env->GetMethodID(jis_class, "<init>", "(Ljava/io/InputStream;)V");
        jobject jar_stream = env->NewObject(jis_class, jis_ctor, bais);

        // step 4: get system classloader as parent for our custom loader
        jclass cl_class = env->FindClass("java/lang/ClassLoader");
        jmethodID get_sys_cl = env->GetStaticMethodID(cl_class, "getSystemClassLoader",
                                                       "()Ljava/lang/ClassLoader;");
        jobject sys_cl = env->CallStaticObjectMethod(cl_class, get_sys_cl);

        // step 5: create a URLClassLoader with no URLs but system CL as parent
        // this gives us a classloader we can GC later for full cleanup
        jclass url_class = env->FindClass("java/net/URL");
        jobjectArray empty_urls = env->NewObjectArray(0, url_class, nullptr);
        jclass ucl_class = env->FindClass("java/net/URLClassLoader");
        jmethodID ucl_ctor = env->GetMethodID(ucl_class, "<init>",
                                               "([Ljava/net/URL;Ljava/lang/ClassLoader;)V");
        jobject custom_cl = env->NewObject(ucl_class, ucl_ctor, empty_urls, sys_cl);

        // step 6: iterate jar entries, collect .class files, defineClass each
        jclass je_class = env->FindClass("java/util/jar/JarEntry");
        jmethodID get_next = env->GetMethodID(jis_class, "getNextJarEntry",
                                               "()Ljava/util/jar/JarEntry;");
        jmethodID get_name = env->GetMethodID(je_class, "getName", "()Ljava/lang/String;");
        jmethodID jis_read = env->GetMethodID(jis_class, "readAllBytes", "()[B");

        // we need defineClass which is protected — use reflection to access it
        jclass class_class = env->FindClass("java/lang/Class");
        jmethodID get_declared_method = env->GetMethodID(
            class_class, "getDeclaredMethod",
            "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");

        jclass method_class = env->FindClass("java/lang/reflect/Method");
        jmethodID set_accessible = env->GetMethodID(method_class, "setAccessible", "(Z)V");
        jmethodID method_invoke = env->GetMethodID(method_class, "invoke",
            "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");

        // get ClassLoader.defineClass(String, byte[], int, int)
        jstring define_name = env->NewStringUTF("defineClass");
        jclass string_class = env->FindClass("java/lang/String");
        jclass byte_arr_class = env->FindClass("[B");
        jclass int_class = env->FindClass("java/lang/Integer");
        jmethodID int_type_field;

        // build param types array for defineClass(String, byte[], int, int)
        jfieldID type_fid = env->GetStaticFieldID(int_class, "TYPE", "Ljava/lang/Class;");
        jobject int_type = env->GetStaticObjectField(int_class, type_fid);
        jobjectArray param_types = env->NewObjectArray(4, class_class, nullptr);
        env->SetObjectArrayElement(param_types, 0, string_class);
        env->SetObjectArrayElement(param_types, 1, byte_arr_class);
        env->SetObjectArrayElement(param_types, 2, int_type);
        env->SetObjectArrayElement(param_types, 3, int_type);

        jobject define_method = env->CallObjectMethod(
            env->GetObjectClass(custom_cl), get_declared_method, define_name, param_types);

        if (!define_method || env->ExceptionCheck()) {
            env->ExceptionClear();
            // fallback: try on ClassLoader class directly
            define_method = env->CallObjectMethod(
                cl_class, get_declared_method, define_name, param_types);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }

        if (define_method) {
            env->CallVoidMethod(define_method, set_accessible, JNI_TRUE);

            jmethodID int_valueof = env->GetStaticMethodID(int_class, "valueOf",
                                                            "(I)Ljava/lang/Integer;");
            jobject zero = env->CallStaticObjectMethod(int_class, int_valueof, 0);

            jclass agent_class = nullptr;
            jstring agent_class_name = nullptr;

            // read all jar entries
            while (true) {
                jobject entry = env->CallObjectMethod(jar_stream, get_next);
                if (!entry || env->ExceptionCheck()) {
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    break;
                }

                jstring entry_name = (jstring)env->CallObjectMethod(entry, get_name);
                const char* name_chars = env->GetStringUTFChars(entry_name, nullptr);
                bool is_class = false;
                bool is_agent = false;
                char class_name_buf[512] = {};

                int len = static_cast<int>(strlen(name_chars));
                if (len > 6 && strcmp(name_chars + len - 6, ".class") == 0) {
                    is_class = true;
                    // convert path to class name: dev/trigger/agent/Foo.class -> dev.trigger.agent.Foo
                    strncpy(class_name_buf, name_chars, len - 6);
                    for (int i = 0; i < len - 6; i++) {
                        if (class_name_buf[i] == '/') class_name_buf[i] = '.';
                    }
                    if (strstr(class_name_buf, "TriggerAgent") != nullptr) {
                        is_agent = true;
                    }
                }

                env->ReleaseStringUTFChars(entry_name, name_chars);

                if (is_class) {
                    // read the class bytes
                    jbyteArray class_data = (jbyteArray)env->CallObjectMethod(jar_stream, jis_read);
                    if (class_data && !env->ExceptionCheck()) {
                        jint class_len = env->GetArrayLength(class_data);
                        jobject len_obj = env->CallStaticObjectMethod(int_class, int_valueof, class_len);

                        jstring cls_name = env->NewStringUTF(class_name_buf);

                        // defineClass(name, bytes, 0, length)
                        jobjectArray invoke_args = env->NewObjectArray(4,
                            env->FindClass("java/lang/Object"), nullptr);
                        env->SetObjectArrayElement(invoke_args, 0, cls_name);
                        env->SetObjectArrayElement(invoke_args, 1, class_data);
                        env->SetObjectArrayElement(invoke_args, 2, zero);
                        env->SetObjectArrayElement(invoke_args, 3, len_obj);

                        jobject defined = env->CallObjectMethod(
                            define_method, method_invoke, custom_cl, invoke_args);

                        if (env->ExceptionCheck()) {
                            env->ExceptionClear();
                        } else if (is_agent && defined) {
                            agent_class = (jclass)env->NewGlobalRef(defined);
                            agent_class_name = (jstring)env->NewGlobalRef(cls_name);
                        }
                    } else if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                    }
                }
            }

            // step 7: call TriggerAgent.agentmain(null, null) — instrumentation not needed
            // our agent is designed to work without it when loaded this way
            if (agent_class) {
                jmethodID agentmain = env->GetStaticMethodID(
                    agent_class, "agentmain",
                    "(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V");

                if (agentmain && !env->ExceptionCheck()) {
                    env->CallStaticVoidMethod(agent_class, agentmain, nullptr, nullptr);
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    success = true;
                } else {
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    // try no-arg start method
                    agentmain = env->GetStaticMethodID(agent_class, "start", "()V");
                    if (agentmain && !env->ExceptionCheck()) {
                        env->CallStaticVoidMethod(agent_class, agentmain);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                        success = true;
                    }
                }
                env->DeleteGlobalRef(agent_class);
                if (agent_class_name) env->DeleteGlobalRef(agent_class_name);
            }
        }

        // close streams
        jmethodID close = env->GetMethodID(jis_class, "close", "()V");
        env->CallVoidMethod(jar_stream, close);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

cleanup:
    if (did_attach) {
        vm->DetachCurrentThread();
    }
    return success;
}
