package com.core.gl;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Independent GLES3 consumer; the official producer retains its original GLES2 context. */
final class ArtemisOfficialSurfaceProxy {
    private static final long WORKER_WAIT_SECONDS = 4L;
    private static final long RELEASE_RETRY_MS = 1000L;
    private static final String VERTEX = "#version 300 es\n"
            + "out vec2 uv; void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));"
            + "uv=p;gl_Position=vec4(p*2.0-1.0,0.0,1.0);}";
    private static final String OES = "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\n"
            + "precision highp float;in vec2 uv;uniform samplerExternalOES tex;uniform mat4 transform;out vec4 color;"
            + "void main(){color=vec4(texture(tex,(transform*vec4(uv,0.0,1.0)).xy).rgb,1.0);}";
    private static final String COPY = "#version 300 es\nprecision highp float;in vec2 uv;uniform sampler2D tex;out vec4 color;"
            + "void main(){color=vec4(texture(tex,uv).rgb,1.0);}";
    private static final class Frame {
        final int width, height; final boolean original;
        Frame(int width, int height, boolean original) { this.width=width; this.height=height; this.original=original; }
    }
    private static final class Disconnect {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean detached = new AtomicBoolean();
    }
    private final Context context;
    private final GraphicsFilterChain chain;
    private final boolean analyzer;
    private final ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
    private final HandlerThread thread = new HandlerThread("Artemis-official-GLES");
    private final Handler worker;
    private final Map<Long, Frame> frames = new LinkedHashMap<>();
    private final int[] scratch = new int[1];
    private final float[] transform = new float[16];
    private volatile boolean closed, failed, outputWanted, workerTimedOut;
    private Disconnect pendingDisconnect; // Accessed under this instance's monitor.
    private boolean releaseDeferredLogged; // GLES-thread state only.
    private volatile int state = GraphicsStatus.WAITING;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface parking = EGL14.EGL_NO_SURFACE, output = EGL14.EGL_NO_SURFACE;
    private EGLConfig config;
    private SurfaceTexture texture;
    private Surface producer, screen;
    private int oes, input, fbo, vao, oesProgram, copyProgram, inputWidth, inputHeight;
    private int screenWidth, screenHeight;
    private GraphicsFilterPipeline pipeline;
    private boolean queued, initialized;

    ArtemisOfficialSurfaceProxy(Context context, GraphicsFilterChain chain, boolean analyzer) {
        this.context=context;this.chain=chain;this.analyzer=analyzer;
        thread.start();worker=new Handler(thread.getLooper());
    }
    Surface connect(Surface nextScreen, int width, int height, int producerWidth, int producerHeight) {
        if (closed || failed) { nextScreen.release();return null; }
        AtomicReference<Surface> pendingScreen = new AtomicReference<>(nextScreen);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Surface> result = new AtomicReference<>();
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        if (!worker.post(() -> {
            // Claim before using the Surface. A timeout may release an unclaimed
            // Surface even if this job never runs; claimed ones stay GL-owned.
            Surface target = pendingScreen.getAndSet(null);
            if (target == null) { done.countDown();return; }
            boolean transferred = false;
            try {
                if (cancelled.get() || closed || failed) throw new IllegalStateException("Consumer unavailable");
                if (texture == null) initialize(producerWidth, producerHeight);
                if (cancelled.get() || closed || failed) throw new IllegalStateException("Consumer unavailable");
                if (!detach()) throw new IllegalStateException("Previous display still connected");
                if (cancelled.get() || closed || failed) throw new IllegalStateException("Consumer unavailable");
                texture.setDefaultBufferSize(producerWidth, producerHeight);
                synchronized (this) { pendingDisconnect = null; }
                screen = target;screenWidth=width;screenHeight=height;transferred=true;
                output=EGL14.eglCreateWindowSurface(display,config,screen,new int[]{EGL14.EGL_NONE},0);
                if (output==EGL14.EGL_NO_SURFACE || !current(output)) throw new IllegalStateException("Display unavailable");
                if (cancelled.get() || closed || failed) throw new IllegalStateException("Consumer unavailable");
                EGL14.eglSwapInterval(display,0);stats.surface(width,height);outputWanted=true;
                result.set(producer);
            } catch (RuntimeException failure) {
                error.set(failure);fail(failure);
            } finally {
                try {
                    if (!transferred) target.release();
                    else if (screen == target && (cancelled.get() || closed || failed)) {
                        outputWanted=false;
                        // If detach fails, keep ownership until native retries
                        // disconnect; never release an active EGL window here.
                        detach();
                    }
                } finally { done.countDown(); }
            }
        })) { pendingScreen.getAndSet(null).release();return null; }
        if (!await(done)) {
            cancelled.set(true);outputWanted=false;failed=true;workerTimedOut=true;state=GraphicsStatus.NATIVE_FAILED;
            Surface unclaimed = pendingScreen.getAndSet(null);
            if (unclaimed != null) unclaimed.release();
            // Covers completion racing the timeout after the job's final check.
            // Cleanup remains serialized with EGL use on the consumer thread.
            worker.post(() -> { if (screen == nextScreen) { outputWanted=false;detach(); } });
            throw new IllegalStateException("Consumer connection timed out");
        }
        if (error.get()!=null) throw error.get();
        return result.get();
    }
    boolean resizeProducer(int width, int height) {
        if (closed || failed) return false;
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean resized = new java.util.concurrent.atomic.AtomicBoolean();
        if (!worker.post(() -> {
            try {
                if (closed || failed || texture == null) return;
                texture.setDefaultBufferSize(width, height);
                resized.set(true);
            } catch (RuntimeException failure) {
                fail(failure);
            } finally {
                done.countDown();
            }
        })) return false;
        if (!await(done)) {
            failed = true;
            workerTimedOut = true;
            state = GraphicsStatus.NATIVE_FAILED;
            return false;
        }
        return resized.get();
    }
    void submitted(long timestamp, int width, int height, boolean original) {
        synchronized (frames) {
            frames.put(timestamp,new Frame(width,height,original));
            while (frames.size()>12) frames.remove(frames.keySet().iterator().next());
        }
    }
    boolean failed() { return failed || closed; }
    ArtemisGraphicsStats stats() { return stats; }
    int status() { return !outputWanted ? (failed ? GraphicsStatus.NATIVE_FAILED : GraphicsStatus.WAITING) : state; }
    synchronized boolean disconnect() {
        outputWanted=false;
        if (pendingDisconnect != null && pendingDisconnect.done.getCount() == 0
                && !pendingDisconnect.detached.get()) pendingDisconnect = null;
        if (pendingDisconnect == null) {
            Disconnect request = new Disconnect();
            pendingDisconnect = request;
            if (!worker.post(() -> {
                try { request.detached.set(detach()); }
                catch (RuntimeException failure) { fail(failure); }
                finally { request.done.countDown(); }
            })) request.done.countDown();
        }
        // A timed-out job still owns EGL and its Surface. Poll one queued
        // disconnection instead of waiting another four seconds on every swap.
        if (!workerTimedOut && !await(pendingDisconnect.done)) {
            workerTimedOut=true;failed=true;state=GraphicsStatus.NATIVE_FAILED;
        }
        return pendingDisconnect.done.getCount() == 0 && pendingDisconnect.detached.get();
    }
    private static boolean await(CountDownLatch done) {
        try { return done.await(WORKER_WAIT_SECONDS,TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {Thread.currentThread().interrupt();return false;}
    }
    private void initialize(int width,int height) {
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version=new int[2];
        if (display==EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display,version,0,version,1)) throw new IllegalStateException("EGL unavailable");
        initialized=true;
        int[] attributes={EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT|EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE};
        EGLConfig[] configs=new EGLConfig[1];
        if (!EGL14.eglChooseConfig(display,attributes,0,configs,0,1,scratch,0)||scratch[0]<1) throw new IllegalStateException("GLES3 unavailable");
        config=configs[0];
        eglContext=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
        parking=EGL14.eglCreatePbufferSurface(display,config,new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
        if (!current(parking)) throw new IllegalStateException("GLES3 context unavailable");
        String extensions=GLES30.glGetString(GLES30.GL_EXTENSIONS);
        if (extensions==null||!extensions.contains("GL_OES_EGL_image_external_essl3")) throw new IllegalStateException("External textures unavailable");
        oesProgram=program(OES);copyProgram=program(COPY);
        GLES30.glGenVertexArrays(1,scratch,0);vao=scratch[0];
        GLES30.glGenTextures(1,scratch,0);oes=scratch[0];
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oes);parameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        texture=new SurfaceTexture(oes);texture.setDefaultBufferSize(width,height);producer=new Surface(texture);
        texture.setOnFrameAvailableListener(ignored -> {
            if (closed||queued) return;
            queued=true;
            worker.post(() -> {
                queued=false;if(closed) return;
                try {
                    if (!current(parking)) throw new IllegalStateException("Consumer context lost");
                    texture.updateTexImage();
                    long stamp=texture.getTimestamp(); Frame frame;
                    synchronized(frames) {frame=frames.remove(stamp);frames.keySet().removeIf(key -> key<stamp);}
                    // A timestamp belongs to the dimensions verified before that exact producer swap.
                    if (frame==null || !outputWanted || failed) return;
                    texture.getTransformMatrix(transform);
                    draw(frame);
                } catch(RuntimeException failure) {fail(failure);}
            });
        },worker);
        check("initialize");
    }
    private void allocate(int width,int height) {
        if (width==inputWidth && height==inputHeight) return;
        long reserve=Math.multiplyExact(Math.multiplyExact((long)width,height),16L);
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE,scratch,0);
        if (width<=0||height<=0||width>scratch[0]||height>scratch[0]||reserve>=Anime4kPostProcessor.TARGET_MEMORY_BUDGET_BYTES)
            throw new IllegalStateException("Input size unavailable");
        if (pipeline!=null) pipeline.release();
        if(input!=0) {scratch[0]=input;GLES30.glDeleteTextures(1,scratch,0);}
        if(fbo!=0) {scratch[0]=fbo;GLES30.glDeleteFramebuffers(1,scratch,0);}
        GLES30.glGenTextures(1,scratch,0);input=scratch[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,input);parameters(GLES30.GL_TEXTURE_2D);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,0);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA8,width,height,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null);
        GLES30.glGenFramebuffers(1,scratch,0);fbo=scratch[0];GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,input,0);
        if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Input target unavailable");
        pipeline=new GraphicsFilterPipeline(context,chain,reserve);if(analyzer)pipeline.setProfiler(stats,true);
        inputWidth=width;inputHeight=height;
    }
    private void draw(Frame frame) {
        allocate(frame.width,frame.height);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);setup(frame.width,frame.height);
        GLES30.glUseProgram(oesProgram);GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(oesProgram,"transform"),1,false,transform,0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindSampler(0,0);GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oes);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(oesProgram,"tex"),0);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);check("normalize");
        int result=input,ow=frame.width,oh=frame.height;
        if(frame.original) {
            result=pipeline.renderTexture(input,frame.width,frame.height,screenWidth,screenHeight);
            ow=pipeline.outputWidth();oh=pipeline.outputHeight();
            state=pipeline.isFailed()?GraphicsStatus.FILTER_FAILED:pipeline.bypassReason()!=Anime4kPostProcessor.BYPASS_NONE?
                    GraphicsStatus.SIZE_UNAVAILABLE:GraphicsStatus.READY;
        } else state=GraphicsStatus.WAITING;
        GraphicsOutputSize.Size v=GraphicsOutputSize.fit(frame.width,frame.height,screenWidth,screenHeight);
        if(v==null || !outputWanted) return;
        if(!current(output)) throw new IllegalStateException("Display context unavailable");
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);setup(screenWidth,screenHeight);
        GLES30.glClearColor(0,0,0,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glViewport((screenWidth-v.width)/2,(screenHeight-v.height)/2,v.width,v.height);
        GLES30.glUseProgram(copyProgram);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindSampler(0,0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,result);GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProgram,"tex"),0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);check("present");
        if (!outputWanted) return;
        if(!EGL14.eglSwapBuffers(display,output))throw new IllegalStateException("Display swap failed");
        if(analyzer&&frame.original){stats.frame(frame.width,frame.height,ow,oh,v.width,v.height);stats.presented();}
    }
    private void setup(int width,int height){
        GLES30.glBindVertexArray(vao);GLES30.glDisable(GLES30.GL_BLEND);GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);GLES30.glDisable(GLES30.GL_STENCIL_TEST);GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glColorMask(true,true,true,true);GLES30.glViewport(0,0,width,height);
    }
    private boolean current(EGLSurface target){return target!=EGL14.EGL_NO_SURFACE&&eglContext!=EGL14.EGL_NO_CONTEXT&&EGL14.eglMakeCurrent(display,target,target,eglContext);}
    private boolean detach(){
        if(display==EGL14.EGL_NO_DISPLAY)return true;
        if(!current(parking)&&!EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT))return false;
        if(output!=EGL14.EGL_NO_SURFACE&&!EGL14.eglDestroySurface(display,output))return false;
        output=EGL14.EGL_NO_SURFACE;
        if(screen!=null)screen.release();screen=null;return true;
    }
    private static void parameters(int target){
        GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);
        GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(target,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
    }
    private static int shader(int kind,String source){
        int shader=GLES30.glCreateShader(kind);GLES30.glShaderSource(shader,source);GLES30.glCompileShader(shader);
        int[] ok=new int[1];GLES30.glGetShaderiv(shader,GLES30.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0){String log=GLES30.glGetShaderInfoLog(shader);GLES30.glDeleteShader(shader);throw new IllegalStateException(log);}return shader;
    }
    private static int program(String source){
        int vertex=shader(GLES30.GL_VERTEX_SHADER,VERTEX),fragment=0,program=0;
        try{fragment=shader(GLES30.GL_FRAGMENT_SHADER,source);program=GLES30.glCreateProgram();GLES30.glAttachShader(program,vertex);GLES30.glAttachShader(program,fragment);
            GLES30.glLinkProgram(program);int[] ok=new int[1];GLES30.glGetProgramiv(program,GLES30.GL_LINK_STATUS,ok,0);
            if(ok[0]==0)throw new IllegalStateException(GLES30.glGetProgramInfoLog(program));return program;
        }catch(RuntimeException failure){if(program!=0)GLES30.glDeleteProgram(program);throw failure;}
        finally{GLES30.glDeleteShader(vertex);if(fragment!=0)GLES30.glDeleteShader(fragment);}
    }
    private static void check(String operation){int error=GLES30.glGetError();if(error!=GLES30.GL_NO_ERROR)throw new IllegalStateException(operation+": GL "+error);}
    private void fail(RuntimeException failure){failed=true;state=GraphicsStatus.NATIVE_FAILED;android.util.Log.w("ArtemisOfficial","Consumer failed; requesting direct output",failure);}
    void release(){
        if(closed)return;closed=true;outputWanted=false;
        worker.post(this::finishRelease);
    }
    private void finishRelease() {
        if (dispose()) { thread.quitSafely();return; }
        if (!releaseDeferredLogged) {
            releaseDeferredLogged=true;
            android.util.Log.w("ArtemisOfficial", "EGL cleanup deferred until owned surfaces can disconnect");
        }
        // Only this owner thread retries cleanup; no other thread releases a
        // window that may still be current after a driver failure.
        worker.postDelayed(this::finishRelease, RELEASE_RETRY_MS);
    }
    private boolean dispose(){
        boolean live=display!=EGL14.EGL_NO_DISPLAY&&current(parking);
        if(texture!=null)texture.setOnFrameAvailableListener(null);
        if(producer!=null){producer.release();producer=null;}
        if(texture!=null){texture.release();texture=null;}
        if(pipeline!=null){if(live)pipeline.release();else pipeline.abandonContext();pipeline=null;}
        if(live){GLES30.glDeleteProgram(oesProgram);GLES30.glDeleteProgram(copyProgram);
            scratch[0]=fbo;GLES30.glDeleteFramebuffers(1,scratch,0);scratch[0]=input;GLES30.glDeleteTextures(1,scratch,0);
            scratch[0]=oes;GLES30.glDeleteTextures(1,scratch,0);scratch[0]=vao;GLES30.glDeleteVertexArrays(1,scratch,0);}
        oesProgram=copyProgram=fbo=input=oes=vao=0;
        if(display!=EGL14.EGL_NO_DISPLAY){
            boolean unbound=EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            boolean released=EGL14.eglReleaseThread();
            if(!unbound&&!released)return false;
            if(output!=EGL14.EGL_NO_SURFACE){
                if(!EGL14.eglDestroySurface(display,output))return false;
                output=EGL14.EGL_NO_SURFACE;
            }
            if(screen!=null){screen.release();screen=null;}
            if(parking!=EGL14.EGL_NO_SURFACE){
                if(!EGL14.eglDestroySurface(display,parking))return false;
                parking=EGL14.EGL_NO_SURFACE;
            }
            if(eglContext!=EGL14.EGL_NO_CONTEXT){
                if(!EGL14.eglDestroyContext(display,eglContext))return false;
                eglContext=EGL14.EGL_NO_CONTEXT;
            }
            if(initialized&&!EGL14.eglTerminate(display))return false;
            initialized=false;display=EGL14.EGL_NO_DISPLAY;
        }
        synchronized(frames){frames.clear();}stats.reset();
        return true;
    }
}
