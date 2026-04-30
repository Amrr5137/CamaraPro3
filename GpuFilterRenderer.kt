package com.example.miappcamarapro3.filters

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renderer GPU para aplicar filtros en tiempo real al preview
 * Usa GPUImage para procesamiento OpenGL ES
 */
class GpuFilterRenderer : GLSurfaceView.Renderer {

    private var gpuImage: GPUImage? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var textureId: Int = -1

    private var vertexShader: Int = -1
    private var fragmentShader: Int = -1
    private var program: Int = -1

    private val vertexShaderCode = """
        attribute vec4 vPosition;
        attribute vec2 vTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 texCoord;
        void main() {
            gl_Position = vPosition;
            texCoord = (uTexMatrix * vec4(vTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 texCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, texCoord);
        }
    """.trimIndent()

    private val vertices = floatArrayOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
    )
    private val texCoords = floatArrayOf(
        0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f
    )

    private val vertexBuffer = java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(vertices); position(0) }

    private val texBuffer = java.nio.ByteBuffer.allocateDirect(texCoords.size * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(texCoords); position(0) }

    private var currentFilter: GPUImageFilter = GPUImageFilter()
    private var filterType: FilterType = FilterType.NORMAL
    private var pendingFilter: GPUImageFilter? = null

    // FBO para conversión de OES a 2D
    private var fboId = -1
    private var fboTextureId = -1
    private var renderWidth = 0
    private var renderHeight = 0

    // Callback cuando el surface está listo para la cámara
    var onSurfaceReady: ((Surface) -> Unit)? = null
    var onRequestRender: (() -> Unit)? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Compilar shaders para el paso OES -> 2D
        vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vertexShader)
            GLES20.glAttachShader(this, fragmentShader)
            GLES20.glLinkProgram(this)
        }

        // Crear texture para SurfaceTexture de la cámara (OES)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(textureId)
        st.setOnFrameAvailableListener {
            onRequestRender?.invoke()
        }
        surfaceTexture = st
        surface = Surface(st)

        // Inicializar filtro inicial
        currentFilter.ifNeedInit()

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onSurfaceReady?.invoke(surface!!)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        renderWidth = width
        renderHeight = height
        
        setupFbo(width, height)
        currentFilter.onOutputSizeChanged(width, height)
    }

    private fun setupFbo(width: Int, height: Int) {
        if (fboId != -1) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
        }

        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        fboTextureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTextureId, 0)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private val transformMatrix = FloatArray(16)
    var screenRotation: Int = 0 // 0, 90, 180, 270

    override fun onDrawFrame(gl: GL10?) {
        try {
            val st = surfaceTexture ?: return
            
            st.updateTexImage()
            st.getTransformMatrix(transformMatrix)

            // Manejar cambio de filtro pendiente (debe ocurrir en el hilo GL)
            pendingFilter?.let {
                currentFilter.destroy()
                currentFilter = it
                currentFilter.ifNeedInit()
                currentFilter.onOutputSizeChanged(renderWidth, renderHeight)
                pendingFilter = null
            }

            // Paso 1: OES -> FBO (Textura 2D normal)
            // Aplicamos aquí la rotación y el transform de la cámara
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, renderWidth, renderHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            Matrix.translateM(transformMatrix, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(transformMatrix, 0, -screenRotation.toFloat(), 0f, 0f, 1f)
            Matrix.translateM(transformMatrix, 0, -0.5f, -0.5f, 0f)

            GLES20.glUseProgram(program)
            val posHandle = GLES20.glGetAttribLocation(program, "vPosition")
            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

            val texHandle = GLES20.glGetAttribLocation(program, "vTexCoord")
            GLES20.glEnableVertexAttribArray(texHandle)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 8, texBuffer)

            val matrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, transformMatrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Paso 2: FBO (2D) -> Pantalla aplicando el Filtro de GPUImage
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, renderWidth, renderHeight)
            
            // Los filtros de GPUImage esperan coordenadas de textura estándar (invertidas en Y para OpenGL)
            // pero como ya las procesamos en el FBO, usamos un buffer simple
            currentFilter.onDraw(fboTextureId, vertexBuffer, texBuffer)

            GLES20.glDisableVertexAttribArray(posHandle)
            GLES20.glDisableVertexAttribArray(texHandle)
        } catch (e: Exception) {
            android.util.Log.e("GpuFilterRenderer", "Draw error", e)
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }

    fun updateFilter(filterType: FilterType) {
        this.filterType = filterType
        val newFilter = when (filterType) {
            FilterType.NORMAL, FilterType.NONE -> GPUImageFilter()
            FilterType.BLACK_WHITE, FilterType.MONOCHROME -> GPUImageGrayscaleFilter()
            FilterType.SEPIA -> GPUImageSepiaToneFilter()
            FilterType.NEGATIVE, FilterType.INVERT -> GPUImageColorInvertFilter()
            FilterType.SIMULATED_IR -> {
                val filter = GPUImageFalseColorFilter()
                filter.setFirstColor(floatArrayOf(0.0f, 0.0f, 0.5f))
                filter.setSecondColor(floatArrayOf(1.0f, 1.0f, 1.0f))
                filter
            }

            FilterType.NIGHT_VISION -> {
                val filter = GPUImageFalseColorFilter()
                filter.setFirstColor(floatArrayOf(0.0f, 0.5f, 0.0f))
                filter.setSecondColor(floatArrayOf(0.0f, 1.0f, 0.0f))
                filter
            }

            FilterType.THERMAL_SIM -> {
                val filter = GPUImageFalseColorFilter()
                filter.setFirstColor(floatArrayOf(0.0f, 0.0f, 0.0f))
                filter.setSecondColor(floatArrayOf(1.0f, 0.0f, 0.0f))
                filter
            }

            FilterType.SKETCH -> GPUImageSketchFilter()
        }
        pendingFilter = newFilter
    }

    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture

    fun release() {
        onSurfaceReady = null
        onRequestRender = null
        
        try {
            surface?.release()
            surface = null
            surfaceTexture?.release()
            surfaceTexture = null
        } catch (e: Exception) {
            android.util.Log.e("GpuFilterRenderer", "Error releasing surface", e)
        }
    }
}

/**
 * Filtro Sketch personalizado para GPUImage
 */
class GPUImageSketchFilter : GPUImageFilter(
    NO_FILTER_VERTEX_SHADER,
    """
    precision mediump float;
    varying vec2 textureCoordinate;
    uniform sampler2D inputImageTexture;
    
    void main() {
        vec3 color = texture2D(inputImageTexture, textureCoordinate).rgb;
        vec3 gray = vec3(dot(color, vec3(0.299, 0.587, 0.114)));
        vec3 inverted = 1.0 - gray;
        
        // Simulación simplificada de sketch
        float edge = length(gray - inverted);
        vec3 sketch = vec3(1.0 - smoothstep(0.0, 0.5, edge));
        
        gl_FragColor = vec4(sketch, 1.0);
    }
    """
)
