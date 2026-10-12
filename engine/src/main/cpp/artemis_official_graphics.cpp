// Fixed official Artemis binaries keep their original GLES context and game loop.
// Only this library's PLT calls are redirected; libEGL and other engines are untouched.
#include "artemis_official_graphics.h"
#include "artemis_host_class.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <android/input.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <map>
#include <vector>
#include <algorithm>

namespace {
constexpr const char* kDisplay = "_ZN7artemis6CStage7DisplayEv";
using StageDisplay = void (*)(void*);
StageDisplay stageDisplay = nullptr;
JavaVM* vm = nullptr;
jclass bridge = nullptr;
jmethodID connectMethod = nullptr, submittedMethod = nullptr, healthyMethod = nullptr,
        disconnectMethod = nullptr, resizeMethod = nullptr, statusMethod = nullptr, directRestoredMethod = nullptr;
struct Env {
    JNIEnv* value = nullptr; bool attached = false;
    Env() {
        if (vm && vm->GetEnv(reinterpret_cast<void**>(&value), JNI_VERSION_1_6) != JNI_OK) {
            attached = vm->AttachCurrentThread(&value, nullptr) == JNI_OK;
            if (!attached) value = nullptr;
        }
    }
    ~Env() { if (attached) vm->DetachCurrentThread(); }
    bool clear() { if (!value || !value->ExceptionCheck()) return false;value->ExceptionClear();return true; }
};
struct SurfaceState {
    EGLSurface actual = EGL_NO_SURFACE;
    EGLConfig config = nullptr;
    ANativeWindow* screen = nullptr;
    ANativeWindow* producer = nullptr;
    bool direct = false;
    int producerWidth = 0, producerHeight = 0;
};
// Official EGL and input calls belong to its one native worker thread.
std::map<EGLSurface, SurfaceState> surfaces;
std::atomic<int> logicalWidth{0}, logicalHeight{0}, screenWidth{0}, screenHeight{0};
std::atomic<bool> directOutput{false};
thread_local bool stageFrame = false;
thread_local int stageTargetWidth = 0, stageTargetHeight = 0;
// BeginRender may destroy and recreate all official EGL resources inside Display.
// Keep this target separate from the current surface/input dimensions.
struct StageScope {
    const bool previousFrame = stageFrame;
    const int previousWidth = stageTargetWidth, previousHeight = stageTargetHeight;
    StageScope(int width, int height) {
        stageFrame = false;
        stageTargetWidth = width;
        stageTargetHeight = height;
    }
    ~StageScope() {
        stageFrame = previousFrame;
        stageTargetWidth = previousWidth;
        stageTargetHeight = previousHeight;
    }
};
bool allowed = false, gesture = false;
PFNEGLPRESENTATIONTIMEANDROIDPROC presentationTime = nullptr;
void status(int code) {
    Env env;if(env.value && bridge){env.value->CallStaticVoidMethod(bridge,statusMethod,code);env.clear();}
}
EGLSurface actual(EGLSurface surface) {
    auto found = surfaces.find(surface);return found == surfaces.end() ? surface : found->second.actual;
}
bool disconnect() {
    Env env;if(!env.value)return false;
    bool result = env.value->CallStaticBooleanMethod(bridge,disconnectMethod);return !env.clear() && result;
}
bool healthy() {
    Env env;if(!env.value)return false;
    bool result = env.value->CallStaticBooleanMethod(bridge,healthyMethod);return !env.clear() && result;
}
void directRestored() {
    Env env;
    if(env.value){env.value->CallStaticVoidMethod(bridge,directRestoredMethod);env.clear();}
    status(5);
}
EGLSurface createDirectSurface(EGLDisplay display,EGLConfig config,EGLNativeWindowType window,const EGLint* attributes) {
    EGLSurface result=eglCreateWindowSurface(display,config,window,attributes);
    if(result!=EGL_NO_SURFACE){directOutput=true;gesture=false;directRestored();}
    else status(3);
    return result;
}
EGLSurface createSurface(EGLDisplay display,EGLConfig config,EGLNativeWindowType window,const EGLint* attributes) {
    if(!allowed || !surfaces.empty())return eglCreateWindowSurface(display,config,window,attributes);
    const char* extensions=eglQueryString(display,EGL_EXTENSIONS);
    if(!extensions || !std::strstr(extensions,"EGL_ANDROID_presentation_time")) {
        status(3);return createDirectSurface(display,config,window,attributes);
    }
    presentationTime=reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(eglGetProcAddress("eglPresentationTimeANDROID"));
    if(!presentationTime)return createDirectSurface(display,config,window,attributes);
    Env env;if(!env.value)return createDirectSurface(display,config,window,attributes);
    auto* screen=reinterpret_cast<ANativeWindow*>(window);
    int width=ANativeWindow_getWidth(screen),height=ANativeWindow_getHeight(screen);
    if(width<=0 || height<=0)return createDirectSurface(display,config,window,attributes);
    jobject screenSurface=ANativeWindow_toSurface(env.value,screen);
    if(!screenSurface || env.clear())return createDirectSurface(display,config,window,attributes);
    const bool stageTarget = stageTargetWidth > 0 && stageTargetHeight > 0;
    const int producerWidth = stageTarget ? stageTargetWidth : width;
    const int producerHeight = stageTarget ? stageTargetHeight : height;
    jobject source=env.value->CallStaticObjectMethod(bridge,connectMethod,screenSurface,
            width,height,producerWidth,producerHeight);
    env.value->DeleteLocalRef(screenSurface);
    if(env.clear() || !source) {
        return disconnect() ? createDirectSurface(display,config,window,attributes) : EGL_NO_SURFACE;
    }
    ANativeWindow* producer=ANativeWindow_fromSurface(env.value,source);
    env.value->DeleteLocalRef(source);
    if(!producer || env.clear()) {
        return disconnect() ? createDirectSurface(display,config,window,attributes) : EGL_NO_SURFACE;
    }
    EGLint format=0;
    if(!eglGetConfigAttrib(display,config,EGL_NATIVE_VISUAL_ID,&format) ||
       ANativeWindow_setBuffersGeometry(producer,producerWidth,producerHeight,format)!=0) {
        ANativeWindow_release(producer);
        return disconnect() ? createDirectSurface(display,config,window,attributes) : EGL_NO_SURFACE;
    }
    EGLSurface result=eglCreateWindowSurface(display,config,producer,attributes);
    if(result==EGL_NO_SURFACE){ANativeWindow_release(producer);
        return disconnect() ? createDirectSurface(display,config,window,attributes) : EGL_NO_SURFACE;}
    ANativeWindow_acquire(screen);
    surfaces.emplace(result,SurfaceState{result,config,screen,producer,false,producerWidth,producerHeight});
    screenWidth=width;screenHeight=height;directOutput=false;
    logicalWidth=stageTarget ? producerWidth : 0;
    logicalHeight=stageTarget ? producerHeight : 0;
    if(stageTarget)stageFrame=true;
    __android_log_print(ANDROID_LOG_INFO,"ArtemisOfficial","Producer connected: buffer=%dx%d screen=%dx%d stage=%d",
            producerWidth,producerHeight,width,height,stageTarget);
    return result;
}
EGLBoolean querySurface(EGLDisplay display,EGLSurface surface,EGLint attribute,EGLint* value) {
    return eglQuerySurface(display,actual(surface),attribute,value);
}
EGLBoolean makeCurrent(EGLDisplay display,EGLSurface draw,EGLSurface read,EGLContext context) {
    return eglMakeCurrent(display,actual(draw),actual(read),context);
}
EGLBoolean destroySurface(EGLDisplay display,EGLSurface surface) {
    auto found=surfaces.find(surface);
    if(found==surfaces.end()) {
        EGLBoolean destroyed=eglDestroySurface(display,surface);
        if(destroyed && allowed && surfaces.empty())status(5);
        return destroyed;
    }
    SurfaceState& state=found->second;
    if(!eglDestroySurface(display,state.actual))return EGL_FALSE;
    const bool disconnected=disconnect();
    ANativeWindow_release(state.producer);ANativeWindow_release(state.screen);
    surfaces.erase(found);logicalWidth=logicalHeight=0;directOutput=true;gesture=false;
    // A destroyed producer has no current raw frame. Keep registration ready for
    // the next official surface without displaying the previous READY state.
    status(disconnected ? 5 : 3);
    return EGL_TRUE;
}
bool restoreDirect(EGLDisplay display,SurfaceState& state) {
    if(!disconnect())return false; // Never connect two EGL producers to the screen queue.
    EGLContext context=eglGetCurrentContext();
    EGLSurface restored=eglCreateWindowSurface(display,state.config,state.screen,nullptr);
    if(restored==EGL_NO_SURFACE)return false;
    if(!eglMakeCurrent(display,restored,restored,context)){eglDestroySurface(display,restored);return false;}
    eglDestroySurface(display,state.actual);
    state.actual=restored;state.direct=true;directOutput=true;gesture=false;
    // The proxy failure remains latched, but direct output is healthy and has no filtered data.
    directRestored();
    __android_log_print(ANDROID_LOG_WARN,"ArtemisOfficial","Consumer unavailable; restored official direct output without restarting VM");
    return true;
}
EGLBoolean swap(EGLDisplay display,EGLSurface surface) {
    auto found=surfaces.find(surface);
    if(found==surfaces.end())return eglSwapBuffers(display,surface);
    SurfaceState& state=found->second;
    if(state.direct)return eglSwapBuffers(display,state.actual);
    if(!healthy()) {
        // The current frame was drawn to the old producer. Let the next normal
        // BeginRender see the real screen size and rebuild the engine's scale.
        return restoreDirect(display,state) ? EGL_TRUE : EGL_FALSE;
    }
    EGLint width=0,height=0;
    if(!eglQuerySurface(display,state.actual,EGL_WIDTH,&width)||!eglQuerySurface(display,state.actual,EGL_HEIGHT,&height))return EGL_FALSE;
    GLint viewport[4]={},framebuffer=0;glGetIntegerv(GL_VIEWPORT,viewport);
    glGetIntegerv(GL_FRAMEBUFFER_BINDING,&framebuffer);
    const bool original=stageFrame && framebuffer==0 && width==logicalWidth.load() && height==logicalHeight.load()
            && viewport[0]==0 && viewport[1]==0 && viewport[2]==width && viewport[3]==height;
    const auto stamp=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    if(!presentationTime(display,state.actual,stamp)) {
        return restoreDirect(display,state) ? EGL_TRUE : EGL_FALSE;
    }
    Env env;
    if(!env.value)return restoreDirect(display,state) ? EGL_TRUE : EGL_FALSE;
    env.value->CallStaticVoidMethod(bridge,submittedMethod,static_cast<jlong>(stamp),width,height,original?JNI_TRUE:JNI_FALSE);
    if(env.clear())return restoreDirect(display,state) ? EGL_TRUE : EGL_FALSE;
    return eglSwapBuffers(display,state.actual);
}
void displayStage(void* stage) {
    // The bundled-library Build ID gate selects this layout. Constructors request int
    // width/height; the SIMD SCVTF in SetupScale converts those integer bits.
    int width=0,height=0;
    std::memcpy(&width,static_cast<char*>(stage)+0x100,sizeof(width));
    std::memcpy(&height,static_cast<char*>(stage)+0x104,sizeof(height));
    const bool valid=width>0 && height>0 && width<=8192 && height<=8192 &&
            static_cast<int64_t>(width)*height*16 < 256LL*1024*1024;
    StageScope scope(valid ? width : 0, valid ? height : 0);
    if(valid && !directOutput.load()) {
        for(auto& item:surfaces) {
            auto& state=item.second;
            if(state.direct || item.second.actual!=eglGetCurrentSurface(EGL_DRAW))continue;
            if(state.producerWidth!=width || state.producerHeight!=height) {
                // This is an actual BufferQueue size change, not a forged
                // eglQuerySurface value. Normal BeginRender refreshes the
                // renderer, projection and SetupScale before this frame draws.
                Env env;
                if(!env.value)break;
                const bool resized=env.value->CallStaticBooleanMethod(bridge,resizeMethod,width,height);
                if(env.clear() || !resized)break;
                if(ANativeWindow_setBuffersGeometry(state.producer,width,height,0)!=0)break;
                state.producerWidth=width;state.producerHeight=height;
                __android_log_print(ANDROID_LOG_INFO,"ArtemisOfficial","Logical producer %dx%d",width,height);
            }
            logicalWidth=width;logicalHeight=height;stageFrame=true;break;
        }
    }
    stageDisplay(stage);
}
struct Viewport {float left=0,top=0,width=0,height=0;int sw=0,sh=0;};
Viewport inputViewport() {
    Viewport v;v.sw=logicalWidth.load();v.sh=logicalHeight.load();int dw=screenWidth.load(),dh=screenHeight.load();
    if(directOutput.load()||v.sw<=0||v.sh<=0||dw<=0||dh<=0)return {};
    if(static_cast<int64_t>(dw)*v.sh<=static_cast<int64_t>(dh)*v.sw) {
        v.width=dw;v.height=std::max<int64_t>(1,static_cast<int64_t>(v.sh)*dw/v.sw);
    } else {
        v.width=std::max<int64_t>(1,static_cast<int64_t>(v.sw)*dh/v.sh);v.height=dh;
    }
    v.left=(dw-static_cast<int>(v.width))/2;v.top=(dh-static_cast<int>(v.height))/2;return v;
}
float inputX(const AInputEvent* event,size_t index) {
    float x=AMotionEvent_getX(event,index);auto v=inputViewport();
    return v.sw>0 ? std::clamp((x-v.left)*v.sw/v.width,0.0f,static_cast<float>(v.sw)):x;
}
float inputY(const AInputEvent* event,size_t index) {
    float y=AMotionEvent_getY(event,index);auto v=inputViewport();
    return v.sh>0 ? std::clamp((y-v.top)*v.sh/v.height,0.0f,static_cast<float>(v.sh)):y;
}
int inputEvent(AInputQueue* queue,AInputEvent** event) {
    while(true) {
        int result=AInputQueue_getEvent(queue,event);if(result<0)return result;
        if(AInputEvent_getType(*event)!=AINPUT_EVENT_TYPE_MOTION)return result;
        auto v=inputViewport();if(v.sw<=0)return result;
        int action=AMotionEvent_getAction(*event)&AMOTION_EVENT_ACTION_MASK;
        if(action==AMOTION_EVENT_ACTION_DOWN) {
            float x=AMotionEvent_getX(*event,0),y=AMotionEvent_getY(*event,0);
            gesture=x>=v.left && y>=v.top && x<v.left+v.width && y<v.top+v.height;
        }
        bool deliver=gesture;
        if(action==AMOTION_EVENT_ACTION_UP||action==AMOTION_EVENT_ACTION_CANCEL)gesture=false;
        if(deliver)return result;
        AInputQueue_finishEvent(queue,*event,1); // Black bars cannot start a game gesture.
    }
}
struct Image {uintptr_t base=0;const Elf64_Phdr* phdr=nullptr;size_t count=0;};
int findImage(dl_phdr_info* info,size_t,void* data) {
    auto& image=*static_cast<Image*>(data);
    if(static_cast<uintptr_t>(info->dlpi_addr)!=image.base)return 0;
    image.phdr=info->dlpi_phdr;image.count=info->dlpi_phnum;return 1;
}
// Identify the loaded image without opening or hashing its entire file on the UI thread.
std::string loadedBuildId(const Image& image) {
    for (size_t i = 0; i < image.count; ++i) {
        const auto& noteSegment = image.phdr[i];
        if (noteSegment.p_type != PT_NOTE) continue;
        bool readable = false;
        for (size_t j = 0; j < image.count; ++j) {
            const auto& load = image.phdr[j];
            if (load.p_type != PT_LOAD || !(load.p_flags & PF_R) || noteSegment.p_vaddr < load.p_vaddr) continue;
            Elf64_Addr offset = noteSegment.p_vaddr - load.p_vaddr;
            if (offset <= load.p_memsz && noteSegment.p_memsz <= load.p_memsz - offset) readable = true;
        }
        if (!readable) continue;
        const auto* at = reinterpret_cast<const unsigned char*>(image.base + noteSegment.p_vaddr);
        size_t length = noteSegment.p_memsz;
        while (length >= sizeof(Elf64_Nhdr)) {
            Elf64_Nhdr note{};
            std::memcpy(&note, at, sizeof(note));
            size_t name = (static_cast<size_t>(note.n_namesz) + 3u) & ~size_t{3};
            size_t desc = (static_cast<size_t>(note.n_descsz) + 3u) & ~size_t{3};
            if (name > length - sizeof(note) || desc > length - sizeof(note) - name) break;
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4 && note.n_descsz == 20
                    && std::memcmp(at + sizeof(note), "GNU", 4) == 0) {
                char hex[41]{};
                const unsigned char* id = at + sizeof(note) + name;
                for (size_t n = 0; n < 20; ++n) {
                    hex[2 * n] = "0123456789abcdef"[id[n] >> 4];
                    hex[2 * n + 1] = "0123456789abcdef"[id[n] & 15];
                }
                return hex;
            }
            size_t total = sizeof(note) + name + desc;
            at += total;
            length -= total;
        }
    }
    return {};
}
struct Change {void** slot;void* before;void* after;int protection;};
bool collect(const Image& image,std::vector<Change>& changes,void** slot,void* before,void* after) {
    if(!before || !after || *slot!=before)return false;
    uintptr_t at=reinterpret_cast<uintptr_t>(slot);
    for(size_t i=0;i<image.count;++i) {
        const auto& p=image.phdr[i];
        if(p.p_type!=PT_LOAD || at<image.base+p.p_vaddr || at+sizeof(void*)>image.base+p.p_vaddr+p.p_memsz)continue;
        int protection=PROT_READ|(p.p_flags&PF_W?PROT_WRITE:0)|(p.p_flags&PF_X?PROT_EXEC:0);
        for(size_t j=0;j<image.count;++j){const auto& q=image.phdr[j];if(q.p_type==PT_GNU_RELRO && at>=image.base+q.p_vaddr && at<image.base+q.p_vaddr+q.p_memsz)protection&=~PROT_WRITE;}
        changes.push_back({slot,before,after,protection});return true;
    }return false;
}
bool change(const Change& item,bool restore) {
    long size=sysconf(_SC_PAGESIZE);if(size<=0)return false;
    uintptr_t page=reinterpret_cast<uintptr_t>(item.slot)&~(static_cast<uintptr_t>(size)-1);
    if(mprotect(reinterpret_cast<void*>(page),size,item.protection|PROT_WRITE)!=0)return false;
    __atomic_store_n(item.slot,restore?item.before:item.after,__ATOMIC_RELEASE);
    return mprotect(reinterpret_cast<void*>(page),size,item.protection)==0;
}
uintptr_t address(uintptr_t base,Elf64_Addr pointer){return pointer<base?base+pointer:pointer;}
}

bool installArtemisOfficialGraphics(void* handle,ANativeActivity* activity,const std::string& path) {
    JNIEnv* env=activity->env;
    jclass cls=loadArtemisHostClass(activity,"com.core.gl.ArtemisOfficialBridge");
    if(!cls){
        __android_log_print(ANDROID_LOG_ERROR,"ArtemisOfficial","Host bridge unavailable from Activity class loader");
        return false;
    }
    // Resolve the reporting entry first and clear each lookup exception before
    // making another JNI call. CheckJNI must not abort a recoverable fallback.
    auto method = [&](const char* name, const char* signature) -> jmethodID {
        jmethodID result = env->GetStaticMethodID(cls, name, signature);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
        return result;
    };
    statusMethod = method("nativeStatus", "(I)V");
    directRestoredMethod = method("directRestored", "()V");
    auto failed = [&]() {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (statusMethod) {
            env->CallStaticVoidMethod(cls, statusMethod, 1);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        env->DeleteLocalRef(cls);
        return false;
    };
    if (!statusMethod || !directRestoredMethod) return failed();
    stageDisplay = reinterpret_cast<StageDisplay>(dlsym(handle, kDisplay));
    Dl_info info{};
    if (!stageDisplay || !dladdr(reinterpret_cast<void*>(stageDisplay), &info)) return failed();
    Image image;
    image.base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    dl_iterate_phdr(findImage, &image);
    std::string identity = loadedBuildId(image);
    if (identity.empty()) return failed();
    jmethodID verify = method("verifyLibrary", "(Ljava/lang/String;Ljava/lang/String;)Z");
    if (!verify) return failed();
    jstring file = env->NewStringUTF(path.c_str());
    if (env->ExceptionCheck() || !file) return failed();
    jstring buildId = env->NewStringUTF(identity.c_str());
    if (env->ExceptionCheck() || !buildId) { env->DeleteLocalRef(file); return failed(); }
    bool verified = env->CallStaticBooleanMethod(cls, verify, file, buildId);
    env->DeleteLocalRef(file);
    env->DeleteLocalRef(buildId);
    if (env->ExceptionCheck() || !verified) return failed();
    connectMethod = method("connect", "(Landroid/view/Surface;IIII)Landroid/view/Surface;");
    submittedMethod = method("submitted", "(JIIZ)V");
    healthyMethod = method("healthy", "()Z");
    disconnectMethod = method("disconnect", "()Z");
    resizeMethod = method("resizeProducer", "(II)Z");
    if (!connectMethod || !submittedMethod || !healthyMethod || !disconnectMethod || !resizeMethod)
        return failed();
    vm = activity->vm;
    bridge = static_cast<jclass>(env->NewGlobalRef(cls));
    if (env->ExceptionCheck() || !bridge) return failed();
    env->DeleteLocalRef(cls);
    const Elf64_Dyn* dynamic=nullptr;size_t count=0;
    for(size_t i=0;i<image.count;++i)if(image.phdr[i].p_type==PT_DYNAMIC){dynamic=reinterpret_cast<const Elf64_Dyn*>(image.base+image.phdr[i].p_vaddr);count=image.phdr[i].p_memsz/sizeof(Elf64_Dyn);}
    const Elf64_Sym* symbols=nullptr;const char* strings=nullptr;const Elf64_Rela* relocs=nullptr;size_t bytes=0;bool rela=false;
    for(size_t i=0;dynamic && i<count && dynamic[i].d_tag!=DT_NULL;++i){auto& d=dynamic[i];
        if(d.d_tag==DT_SYMTAB)symbols=reinterpret_cast<const Elf64_Sym*>(address(image.base,d.d_un.d_ptr));
        if(d.d_tag==DT_STRTAB)strings=reinterpret_cast<const char*>(address(image.base,d.d_un.d_ptr));
        if(d.d_tag==DT_JMPREL)relocs=reinterpret_cast<const Elf64_Rela*>(address(image.base,d.d_un.d_ptr));
        if(d.d_tag==DT_PLTRELSZ)bytes=d.d_un.d_val;if(d.d_tag==DT_PLTREL)rela=d.d_un.d_val==DT_RELA;
    }
    const std::map<std::string,void*> hooks={{kDisplay,reinterpret_cast<void*>(displayStage)},
        {"eglCreateWindowSurface",reinterpret_cast<void*>(createSurface)},{"eglDestroySurface",reinterpret_cast<void*>(destroySurface)},
        {"eglQuerySurface",reinterpret_cast<void*>(querySurface)},{"eglMakeCurrent",reinterpret_cast<void*>(makeCurrent)},
        {"eglSwapBuffers",reinterpret_cast<void*>(swap)},{"AMotionEvent_getX",reinterpret_cast<void*>(inputX)},
        {"AMotionEvent_getY",reinterpret_cast<void*>(inputY)},{"AInputQueue_getEvent",reinterpret_cast<void*>(inputEvent)}};
    std::vector<Change> changes;std::map<std::string,int> found;bool ok=symbols && strings && relocs && rela && bytes%sizeof(Elf64_Rela)==0;
    if(ok)for(size_t i=0;i<bytes/sizeof(Elf64_Rela);++i) {
        if(ELF64_R_TYPE(relocs[i].r_info)!=R_AARCH64_JUMP_SLOT)continue;
        const char* name=strings+symbols[ELF64_R_SYM(relocs[i].r_info)].st_name;auto hook=hooks.find(name);if(hook==hooks.end())continue;
        void* before=dlsym(handle,name);ok=collect(image,changes,reinterpret_cast<void**>(image.base+relocs[i].r_offset),before,hook->second)&&ok;++found[name];
    }
    for(auto& hook:hooks)if(!found[hook.first])ok=false;
    if(!ok){status(1);return false;}
    for(size_t i=0;i<changes.size();++i)if(!change(changes[i],false)) {
        bool restored=true;size_t applied=i+1;while(applied)restored=change(changes[--applied],true)&&restored;
        status(3);__android_log_print(ANDROID_LOG_ERROR,"ArtemisOfficial","Registration failed; rollback=%d",restored);return false;
    }
    allowed=true;status(5);
    __android_log_print(ANDROID_LOG_INFO,"ArtemisOfficial","Official stage hooks registered");
    return true;
}
