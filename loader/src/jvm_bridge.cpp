// language: C++20, target: Windows 11
// all strings encrypted at compile time — no plaintext JNI signatures in the binary

#include "jvm_bridge.h"
#include "crypt.h"
#include <windows.h>
#include <jni.h>

typedef jint(JNICALL* fn_JNI_GetCreatedJavaVMs)(JavaVM**, jsize, jsize*);

static JavaVM* find_jvm() {
    static constexpr auto enc_dll = ENC("jvm.dll");
    static constexpr auto enc_fn  = ENC("JNI_GetCreatedJavaVMs");

    auto dll_name = DEC(enc_dll);
    HMODULE jvm_dll = GetModuleHandleA(dll_name);
    if (!jvm_dll) return nullptr;

    auto fn_name = DEC(enc_fn);
    auto get_vms = reinterpret_cast<fn_JNI_GetCreatedJavaVMs>(
        GetProcAddress(jvm_dll, fn_name));
    if (!get_vms) return nullptr;

    JavaVM* vm = nullptr;
    jsize count = 0;
    if (get_vms(&vm, 1, &count) != JNI_OK || count == 0) return nullptr;
    return vm;
}

// encrypted JNI class/method strings
static constexpr auto e_bais       = ENC("java/io/ByteArrayInputStream");
static constexpr auto e_init       = ENC("<init>");
static constexpr auto e_bais_sig   = ENC("([B)V");
static constexpr auto e_jis        = ENC("java/util/jar/JarInputStream");
static constexpr auto e_jis_sig    = ENC("(Ljava/io/InputStream;)V");
static constexpr auto e_cl         = ENC("java/lang/ClassLoader");
static constexpr auto e_getsys     = ENC("getSystemClassLoader");
static constexpr auto e_getsys_sig = ENC("()Ljava/lang/ClassLoader;");
static constexpr auto e_url        = ENC("java/net/URL");
static constexpr auto e_ucl        = ENC("java/net/URLClassLoader");
static constexpr auto e_ucl_sig    = ENC("([Ljava/net/URL;Ljava/lang/ClassLoader;)V");
static constexpr auto e_je         = ENC("java/util/jar/JarEntry");
static constexpr auto e_getnext    = ENC("getNextJarEntry");
static constexpr auto e_getnext_sig= ENC("()Ljava/util/jar/JarEntry;");
static constexpr auto e_getname    = ENC("getName");
static constexpr auto e_getname_sig= ENC("()Ljava/lang/String;");
static constexpr auto e_readall    = ENC("readAllBytes");
static constexpr auto e_readall_sig= ENC("()[B");
static constexpr auto e_class      = ENC("java/lang/Class");
static constexpr auto e_gdm        = ENC("getDeclaredMethod");
static constexpr auto e_gdm_sig    = ENC("(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
static constexpr auto e_method     = ENC("java/lang/reflect/Method");
static constexpr auto e_setacc     = ENC("setAccessible");
static constexpr auto e_setacc_sig = ENC("(Z)V");
static constexpr auto e_invoke     = ENC("invoke");
static constexpr auto e_invoke_sig = ENC("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
static constexpr auto e_defcls     = ENC("defineClass");
static constexpr auto e_string     = ENC("java/lang/String");
static constexpr auto e_bytearr    = ENC("[B");
static constexpr auto e_integer    = ENC("java/lang/Integer");
static constexpr auto e_type       = ENC("TYPE");
static constexpr auto e_type_sig   = ENC("Ljava/lang/Class;");
static constexpr auto e_valueof    = ENC("valueOf");
static constexpr auto e_valueof_sig= ENC("(I)Ljava/lang/Integer;");
static constexpr auto e_object     = ENC("java/lang/Object");
static constexpr auto e_close      = ENC("close");
static constexpr auto e_close_sig  = ENC("()V");
static constexpr auto e_agent_mark = ENC("ServiceProvider");
static constexpr auto e_agentmain  = ENC("agentmain");
static constexpr auto e_agentm_sig = ENC("(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V");
static constexpr auto e_start      = ENC("start");
static constexpr auto e_class_ext  = ENC(".class");

bool jvm_load_agent(const uint8_t* jar_bytes, int jar_length) {
    JavaVM* vm = find_jvm();
    if (!vm) return false;

    JNIEnv* env = nullptr;
    bool did_attach = false;

    jint rc = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
    if (rc == JNI_EDETACHED) {
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

    jbyteArray byte_array = env->NewByteArray(jar_length);
    if (!byte_array) goto cleanup;
    env->SetByteArrayRegion(byte_array, 0, jar_length,
                            reinterpret_cast<const jbyte*>(jar_bytes));

    {
        auto d_bais     = DEC(e_bais);
        auto d_init     = DEC(e_init);
        auto d_bais_sig = DEC(e_bais_sig);

        jclass bais_class = env->FindClass(d_bais);
        jmethodID bais_ctor = env->GetMethodID(bais_class, d_init, d_bais_sig);
        jobject bais = env->NewObject(bais_class, bais_ctor, byte_array);

        auto d_jis     = DEC(e_jis);
        auto d_jis_sig = DEC(e_jis_sig);
        jclass jis_class = env->FindClass(d_jis);
        jmethodID jis_ctor = env->GetMethodID(jis_class, d_init, d_jis_sig);
        jobject jar_stream = env->NewObject(jis_class, jis_ctor, bais);

        auto d_cl        = DEC(e_cl);
        auto d_getsys    = DEC(e_getsys);
        auto d_getsys_sig= DEC(e_getsys_sig);
        jclass cl_class = env->FindClass(d_cl);
        jmethodID get_sys_cl = env->GetStaticMethodID(cl_class, d_getsys, d_getsys_sig);
        jobject sys_cl = env->CallStaticObjectMethod(cl_class, get_sys_cl);

        auto d_url     = DEC(e_url);
        auto d_ucl     = DEC(e_ucl);
        auto d_ucl_sig = DEC(e_ucl_sig);
        jclass url_class = env->FindClass(d_url);
        jobjectArray empty_urls = env->NewObjectArray(0, url_class, nullptr);
        jclass ucl_class = env->FindClass(d_ucl);
        jmethodID ucl_ctor = env->GetMethodID(ucl_class, d_init, d_ucl_sig);
        jobject custom_cl = env->NewObject(ucl_class, ucl_ctor, empty_urls, sys_cl);

        auto d_je         = DEC(e_je);
        auto d_getnext    = DEC(e_getnext);
        auto d_getnext_sig= DEC(e_getnext_sig);
        auto d_getname    = DEC(e_getname);
        auto d_getname_sig= DEC(e_getname_sig);
        auto d_readall    = DEC(e_readall);
        auto d_readall_sig= DEC(e_readall_sig);

        jclass je_class = env->FindClass(d_je);
        jmethodID get_next = env->GetMethodID(jis_class, d_getnext, d_getnext_sig);
        jmethodID get_name = env->GetMethodID(je_class, d_getname, d_getname_sig);
        jmethodID jis_read = env->GetMethodID(jis_class, d_readall, d_readall_sig);

        auto d_class   = DEC(e_class);
        auto d_gdm     = DEC(e_gdm);
        auto d_gdm_sig = DEC(e_gdm_sig);
        auto d_method  = DEC(e_method);
        auto d_setacc  = DEC(e_setacc);
        auto d_setacc_sig = DEC(e_setacc_sig);
        auto d_invoke  = DEC(e_invoke);
        auto d_invoke_sig = DEC(e_invoke_sig);

        jclass class_class = env->FindClass(d_class);
        jmethodID get_declared_method = env->GetMethodID(class_class, d_gdm, d_gdm_sig);
        jclass method_class = env->FindClass(d_method);
        jmethodID set_accessible = env->GetMethodID(method_class, d_setacc, d_setacc_sig);
        jmethodID method_invoke = env->GetMethodID(method_class, d_invoke, d_invoke_sig);

        auto d_defcls  = DEC(e_defcls);
        auto d_string  = DEC(e_string);
        auto d_bytearr = DEC(e_bytearr);
        auto d_integer = DEC(e_integer);
        auto d_type    = DEC(e_type);
        auto d_type_sig= DEC(e_type_sig);

        jstring define_name = env->NewStringUTF(d_defcls);
        jclass string_class = env->FindClass(d_string);
        jclass byte_arr_class = env->FindClass(d_bytearr);
        jclass int_class = env->FindClass(d_integer);

        jfieldID type_fid = env->GetStaticFieldID(int_class, d_type, d_type_sig);
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
            define_method = env->CallObjectMethod(
                cl_class, get_declared_method, define_name, param_types);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }

        if (define_method) {
            env->CallVoidMethod(define_method, set_accessible, JNI_TRUE);

            auto d_valueof    = DEC(e_valueof);
            auto d_valueof_sig= DEC(e_valueof_sig);
            jmethodID int_valueof = env->GetStaticMethodID(int_class, d_valueof, d_valueof_sig);
            jobject zero = env->CallStaticObjectMethod(int_class, int_valueof, 0);

            auto d_object     = DEC(e_object);
            auto d_agent_mark = DEC(e_agent_mark);
            auto d_class_ext  = DEC(e_class_ext);

            jclass agent_class = nullptr;

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
                char class_name_buf[512]{};

                int len = static_cast<int>(strlen(name_chars));
                if (len > 6 && strcmp(name_chars + len - 6, d_class_ext) == 0) {
                    is_class = true;
                    strncpy(class_name_buf, name_chars, len - 6);
                    for (int i = 0; i < len - 6; i++)
                        if (class_name_buf[i] == '/') class_name_buf[i] = '.';
                    if (strstr(class_name_buf, d_agent_mark))
                        is_agent = true;
                }

                env->ReleaseStringUTFChars(entry_name, name_chars);

                if (is_class) {
                    jbyteArray class_data = (jbyteArray)env->CallObjectMethod(jar_stream, jis_read);
                    if (class_data && !env->ExceptionCheck()) {
                        jint class_len = env->GetArrayLength(class_data);
                        jobject len_obj = env->CallStaticObjectMethod(int_class, int_valueof, class_len);
                        jstring cls_name = env->NewStringUTF(class_name_buf);

                        jobjectArray invoke_args = env->NewObjectArray(4,
                            env->FindClass(d_object), nullptr);
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
                        }
                    } else if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                    }
                }
            }

            if (agent_class) {
                auto d_agentmain  = DEC(e_agentmain);
                auto d_agentm_sig = DEC(e_agentm_sig);
                auto d_start      = DEC(e_start);
                auto d_close_sig  = DEC(e_close_sig);

                jmethodID agentmain = env->GetStaticMethodID(
                    agent_class, d_agentmain, d_agentm_sig);

                if (agentmain && !env->ExceptionCheck()) {
                    env->CallStaticVoidMethod(agent_class, agentmain, nullptr, nullptr);
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    success = true;
                } else {
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    agentmain = env->GetStaticMethodID(agent_class, d_start, d_close_sig);
                    if (agentmain && !env->ExceptionCheck()) {
                        env->CallStaticVoidMethod(agent_class, agentmain);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                        success = true;
                    }
                }
                env->DeleteGlobalRef(agent_class);
            }
        }

        auto d_close     = DEC(e_close);
        auto d_close_sig2= DEC(e_close_sig);
        jmethodID close = env->GetMethodID(jis_class, d_close, d_close_sig2);
        env->CallVoidMethod(jar_stream, close);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

cleanup:
    if (did_attach) {
        vm->DetachCurrentThread();
    }
    return success;
}
