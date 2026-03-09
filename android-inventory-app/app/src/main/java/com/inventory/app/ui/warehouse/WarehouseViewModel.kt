package com.inventory.app.ui.warehouse

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.inventory.app.domain.model.Product
import com.inventory.app.domain.model.WarehouseRecord
import com.inventory.app.domain.usecase.RecognizeProductUseCase
import com.inventory.app.data.local.db.WarehouseRecordDao
import com.inventory.app.data.repository.ProductRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject

@HiltViewModel
class WarehouseViewModel @Inject constructor(
    private val recognizeProductUseCase: RecognizeProductUseCase,
    private val productRepository: ProductRepository,
    private val warehouseRecordDao: WarehouseRecordDao
) : ViewModel() {

    private val _uiState = MutableLiveData<WarehouseUiState>(WarehouseUiState.Idle)
    val uiState: LiveData<WarehouseUiState> = _uiState

    val products: LiveData<List<Product>> = productRepository.getAllProducts().asLiveData()
    
    private val _pendingCount = MutableLiveData<Int>(0)
    val pendingCount: LiveData<Int> = _pendingCount

    private val pendingRecords = mutableListOf<PendingRecord>()

    /**
     * 识别商品 (调用 Qwen-VL)
     */
    fun recognizeProduct(imageBase64: String) {
        viewModelScope.launch {
            _uiState.value = WarehouseUiState.Recognizing

            // 从配置读取 API Key
            val apiKey = getApiKey()
            if (apiKey.isEmpty()) {
                _uiState.value = WarehouseUiState.RecognitionError("请先配置 DashScope API Key")
                return@launch
            }
            
            val result = recognizeProductUseCase.execute(imageBase64, apiKey)

            result.fold(
                onSuccess = { output ->
                    _uiState.value = WarehouseUiState.RecognitionSuccess(
                        productName = output.productName,
                        matchedProduct = output.matchedProduct,
                        confidence = output.confidence
                    )
                },
                onFailure = { error ->
                    _uiState.value = WarehouseUiState.RecognitionError(error.message ?: "识别失败")
                }
            )
        }
    }
    
    private fun getApiKey(): String {
        // TODO: 从 SharedPreferences 或安全存储读取
        return "sk-your-dashscope-api-key" // 替换为实际 API Key
    }

    /**
     * 用户确认商品
     */
    fun onProductConfirmed(product: Product) {
        viewModelScope.launch {
            // 等待蓝牙秤读数稳定
            val weight = waitForStableWeight()
            
            if (weight != null) {
                pendingRecords.add(
                    PendingRecord(
                        product = product,
                        weight = weight
                    )
                )
                _pendingCount.value = pendingRecords.size
                _uiState.value = WarehouseUiState.Idle
            } else {
                // 如果没有蓝牙秤，允许手动输入重量
                _uiState.value = WarehouseUiState.WaitingForWeight(product)
            }
        }
    }
    
    /**
     * 手动输入重量
     */
    fun onWeightEntered(product: Product, weight: Double) {
        pendingRecords.add(
            PendingRecord(
                product = product,
                weight = weight
            )
        )
        _pendingCount.value = pendingRecords.size
        _uiState.value = WarehouseUiState.Idle
    }

    /**
     * 一键入库
     */
    fun batchImport() {
        viewModelScope.launch {
            val records = pendingRecords.map { pending ->
                WarehouseRecord(
                    productId = pending.product.id,
                    productName = pending.product.name,
                    weight = pending.weight,
                    batchNo = generateBatchNo(),
                    createTime = System.currentTimeMillis(),
                    operator = "当前用户" // TODO: 从用户配置读取
                )
            }

            warehouseRecordDao.insertRecords(records)
            pendingRecords.clear()
            _uiState.value = WarehouseUiState.ImportSuccess(records.size)
        }
    }

    private suspend fun waitForStableWeight(): Double? {
        // TODO: 调用 BluetoothScaleService.waitForStableWeight()
        // 临时返回 null，让 UI 显示手动输入对话框
        return null
    }

    private fun generateBatchNo(): String {
        val timestamp = System.currentTimeMillis()
        return "B${timestamp}"
    }
}

sealed class WarehouseUiState {
    object Idle : WarehouseUiState()
    object Recognizing : WarehouseUiState()
    data class RecognitionSuccess(
        val productName: String,
        val matchedProduct: Product?,
        val confidence: Float
    ) : WarehouseUiState()
    data class RecognitionError(val message: String) : WarehouseUiState()
    data class ImportSuccess(val count: Int) : WarehouseUiState()
    data class WaitingForWeight(val product: Product) : WarehouseUiState()
}

data class PendingRecord(
    val product: Product,
    val weight: Double
)
