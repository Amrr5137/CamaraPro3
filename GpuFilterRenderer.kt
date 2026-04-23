package com.example.miappcamarapro3.filters

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
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
        varying vec2 texCoord;
        void main() {
            gl_Position = vPosition;
            texCoord = vTexCoord;
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
        0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f
    )

    private val vertexBuffer = java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(vertices); position(0) }

    private val texBuffer = java.nio.ByteBuffer.allocateDirect(texCoords.size * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(texCoords); position(0) }

    private var currentFilter: GPUImageFilter = GPUImageFilter() // Filtro normal por defecto
    private var filterType: FilterType = FilterType.NORMAL

    // Callback cuando el surface está listo para la cámara
    var onSurfaceReady: ((Surface) -> Unit)? = null
    var onRequestRender: (() -> Unit)? = null

    fun initialize(width: Int, height: Int) {
        // Inicializar GPUImage
        gpuImage = GPUImage(null) // Contexto se setea después
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Compilar shaders
        vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vertexShader)
            GLES20.glAttachShader(this, fragmentShader)
            GLES20.glLinkProgram(this)
        }

        // Crear texture para SurfaceTexture de la cámara
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        val st = SurfaceTexture(textureId)
        st.setOnFrameAvailableListener {
            onRequestRender?.invoke()
        }
        surfaceTexture = st
        surface = Surface(st)

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onSurfaceReady?.invoke(surface!!)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceTexture?.setDefaultBufferSize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        try {
            val st = surfaceTexture
            if (st == null) return
            
            st.updateTexImage()

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            val posHandle = GLES20.glGetAttribLocation(program, "vPosition")
            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

            val texHandle = GLES20.glGetAttribLocation(program, "vTexCoord")
            GLES20.glEnableVertexAttribArray(texHandle)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 8, texBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

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
        currentFilter = when (filterType) {
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
