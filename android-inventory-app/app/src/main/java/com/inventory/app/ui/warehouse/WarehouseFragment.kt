package com.inventory.app.ui.warehouse

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.inventory.app.R
import com.inventory.app.databinding.FragmentWarehouseBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@AndroidEntryPoint
class WarehouseFragment : Fragment(R.layout.fragment_warehouse) {

    private var _binding: FragmentWarehouseBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WarehouseViewModel by viewModels()
    
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentWarehouseBinding.bind(view)

        setupRecyclerView()
        setupObservers()
        setupClickListeners()
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // 检查并请求权限
        checkPermissions()
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA
        )
        
        val needRequest = permissions.any {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (needRequest) {
            requestPermissionLauncher.launch(permissions)
        } else {
            startCamera()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "相机启动失败", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun setupRecyclerView() {
        binding.recyclerViewProducts.layoutManager = LinearLayoutManager(requireContext())
        viewModel.products.observe(viewLifecycleOwner) { products ->
            val adapter = ProductListAdapter(products) { product ->
                onProductSelected(product)
            }
            binding.recyclerViewProducts.adapter = adapter
        }
    }

    private fun setupObservers() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is WarehouseUiState.Idle -> {
                    binding.progressBar.visibility = View.GONE
                }
                is WarehouseUiState.Recognizing -> {
                    binding.progressBar.visibility = View.VISIBLE
                }
                is WarehouseUiState.RecognitionSuccess -> {
                    binding.progressBar.visibility = View.GONE
                    showRecognitionResult(state)
                }
                is WarehouseUiState.RecognitionError -> {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                }
                is WarehouseUiState.ImportSuccess -> {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "成功入库 ${state.count} 条记录", Toast.LENGTH_SHORT).show()
                    binding.buttonBatchImport.isEnabled = false
                }
                is WarehouseUiState.WaitingForWeight -> {
                    binding.progressBar.visibility = View.GONE
                    showWeightInputDialog(state.product)
                }
            }
        }

        viewModel.pendingCount.observe(viewLifecycleOwner) { count ->
            binding.buttonBatchImport.isEnabled = count > 0
            binding.buttonBatchImport.text = "✅ 一键入库 ($count)"
        }
    }

    private fun setupClickListeners() {
        binding.buttonCapture.setOnClickListener {
            captureImage()
        }

        binding.buttonManualInput.setOnClickListener {
            showManualInputDialog()
        }

        binding.buttonBatchImport.setOnClickListener {
            viewModel.batchImport()
        }
    }

    private fun captureImage() {
        val imageCapture = imageCapture ?: return
        
        val photoFile = java.io.File(
            requireContext().cacheDir,
            "photo_${System.currentTimeMillis()}.jpg"
        )
        
        val outputOptions = ImageCapture.OutputFile.Builder(photoFile).build()
        
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                    if (bitmap != null) {
                        processImage(bitmap)
                    } else {
                        Toast.makeText(requireContext(), "图片处理失败", Toast.LENGTH_SHORT).show()
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    Toast.makeText(requireContext(), "拍照失败：${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun processImage(bitmap: Bitmap) {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
        val imageBytes = byteArrayOutputStream.toByteArray()
        val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        
        viewModel.recognizeProduct(imageBase64)
    }

    private fun onProductSelected(product: Product) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("确认商品")
            .setMessage("确认这是 ${product.name} 吗？")
            .setPositiveButton("确认") { _, _ ->
                viewModel.onProductConfirmed(product)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRecognitionResult(state: WarehouseUiState.RecognitionSuccess) {
        val message = buildString {
            appendLine("识别结果：${state.productName}")
            appendLine("置信度：${(state.confidence * 100).toInt()}%")
            if (state.matchedProduct != null) {
                appendLine("匹配商品库：是")
            } else {
                appendLine("匹配商品库：否（将创建新商品）")
            }
        }
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("识别成功")
            .setMessage(message)
            .setPositiveButton("确认入库") { _, _ ->
                state.matchedProduct?.let { onProductSelected(it) }
                    ?: run {
                        val newProduct = Product(
                            id = "",
                            name = state.productName,
                            category = "",
                            createTime = System.currentTimeMillis()
                        )
                        onProductSelected(newProduct)
                    }
            }
            .setNegativeButton("重新识别", null)
            .show()
    }

    private fun showManualInputDialog() {
        val editText = android.widget.EditText(requireContext())
        editText.hint = "输入商品名称"
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("手动输入商品")
            .setView(editText)
            .setPositiveButton("确认") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    val product = Product(
                        id = "",
                        name = name,
                        category = "",
                        createTime = System.currentTimeMillis()
                    )
                    onProductSelected(product)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showWeightInputDialog(product: Product) {
        val editText = android.widget.EditText(requireContext())
        editText.hint = "输入重量 (kg)"
        editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("输入重量")
            .setMessage("商品：${product.name}\n请输入重量（公斤）")
            .setView(editText)
            .setPositiveButton("确认") { _, _ ->
                val weightStr = editText.text.toString().trim()
                if (weightStr.isNotEmpty()) {
                    try {
                        val weight = weightStr.toDouble()
                        viewModel.onWeightEntered(product, weight)
                    } catch (e: NumberFormatException) {
                        Toast.makeText(requireContext(), "请输入有效的数字", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        cameraExecutor.shutdown()
    }
}
