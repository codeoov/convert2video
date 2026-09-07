# 이미지 업로드 구현 가이드

> **목적**: 신규 프로젝트에서 갤러리 선택 → 압축 → 서버 업로드 흐름을 처음부터 구현한다.
> `happy_v12`에서 실사용 검증된 패턴 기반.

---

## 1. 전체 업로드 체인

```
UI (화면)
  └→ rememberPickAndCropImageLauncher()    ← 갤러리 선택 + 크롭
       └→ viewModel.setSelectedImageUri(uri)

ViewModel.save()
  └→ repository.saveProfile(..., imageUri)

Repository
  ├→ authRepository.saveUserField("nickname", ...)  ← 텍스트 필드
  └→ storageRepository.uploadFromUri(uri, path)     ← 이미지 업로드

StorageRepository.uploadFromUri()
  ├→ ImageCompressUtils.compressForUpload(context, uri)
  │   (max 1200px, JPEG 75% → 500KB 초과 시 65% 재시도)
  ├→ multipartUploadClient.upload(file, path)
  │   (파일명: "file_{timestamp}_{uuid8}.jpg")
  └→ file.delete()   ← 임시 파일 반드시 삭제

API (Retrofit @Multipart POST)
  └→ Response<StorageUploadResponse>
       └→ downloadUrl, thumbnailUrl 반환
```

---

## 2. 파일 구조

```
helpers/
├── ImageFromGalleryHelper.kt        ← 단일 이미지 선택 (크롭 없음)
├── MultiImageFromGalleryHelper.kt   ← 다중 이미지 선택 (최대 N장)
└── PickImageCropHelper.kt           ← 단일 이미지 선택 + 크롭

utils/
└── ImageCompressUtils.kt            ← 업로드 전 압축 (1200px / JPEG)

data/
├── remote/StorageApi.kt             ← @Multipart Retrofit 인터페이스
├── dto/storage/StorageUploadResponse.kt
├── adapters/RetrofitMultipartUploadClient.kt
└── repository/StorageRepository.kt

domain/contracts/
└── MultipartUploadClient.kt         ← 업로드 클라이언트 추상화
```

---

## 3. 갤러리 헬퍼 3종 — 사용 방법

### 헬퍼 1: 단일 이미지 + 크롭 (프로필 사진 등)

```kotlin
// 화면 Composable 내부
val pickAndCrop = rememberPickAndCropImageLauncher(
    onCropped = { uri ->
        uri?.let { viewModel.setSelectedImageUri(it) }
    },
    onPermissionDenied = {
        coroutineScope.launch { snackbarHostState.showSnackbar(context.getString(R.string.permission_denied)) }
    },
)

// 버튼 클릭 시
IconButtonSmall(
    imageVector = Icons.Default.CameraAlt,
    onClick = { pickAndCrop() },
)
```

### 헬퍼 2: 단일 이미지 (크롭 없음)

```kotlin
val pickImage = rememberImageFromGalleryLauncher(
    onSelected = { uri -> uri?.let { viewModel.setImageUri(it) } },
    onPermissionDenied = { /* 권한 거부 처리 */ },
)

PrimaryButtonMedium(text = stringResource(R.string.select_image), onClick = { pickImage() })
```

### 헬퍼 3: 다중 이미지 (게시물 첨부 등)

```kotlin
val pickMultiple = rememberMultiImageFromGalleryLauncher(
    maxCount = 5,
    onSelected = { uris -> viewModel.setImageUris(uris) },
    onPermissionDenied = { /* 권한 거부 처리 */ },
    onExceedMax = {
        coroutineScope.launch { snackbarHostState.showSnackbar(context.getString(R.string.max_images_exceeded)) }
    },
)

SecondaryButtonMedium(text = stringResource(R.string.attach_images), onClick = { pickMultiple() })
```

---

## 4. 갤러리 헬퍼 내부 구현 — `PickImageCropHelper.kt`

```kotlin
@Composable
fun rememberPickAndCropImageLauncher(
    onCropped: (Uri?) -> Unit,
    onPermissionDenied: () -> Unit,
): () -> Unit {
    val context = LocalContext.current

    // 3) 크롭 완료 처리
    val cropLauncher = rememberLauncherForActivityResult(
        contract = CropImageContract(),   // com.canhub.cropper:android-image-cropper
    ) { result ->
        onCropped(if (result.isSuccessful) result.uriContent else null)
    }

    // 2) 갤러리에서 이미지 선택 → 크롭으로 전달
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        cropLauncher.launch(
            CropImageContractOptions(
                uri = uri,
                cropImageOptions = CropImageOptions(
                    guidelines = CropImageView.Guidelines.ON,
                    outputCompressFormat = Bitmap.CompressFormat.JPEG,
                    outputCompressQuality = 90,
                ),
            )
        )
    }

    // 1) 권한 요청
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) galleryLauncher.launch("image/*")
        else onPermissionDenied()
    }

    return {
        if (PermissionHelper.hasPhotoLibraryPermission(context)) {
            galleryLauncher.launch("image/*")
        } else {
            permissionLauncher.launch(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    Manifest.permission.READ_MEDIA_IMAGES
                else
                    Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }
}
```

### `PermissionHelper` (단순 유틸)
```kotlin
object PermissionHelper {
    fun hasPhotoLibraryPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
```

---

## 5. `ImageCompressUtils.kt` — 업로드 전 압축

```kotlin
object ImageCompressUtils {

    private const val MAX_DIMENSION = 1200   // px
    private const val DEFAULT_QUALITY = 75   // JPEG 품질
    private const val FALLBACK_QUALITY = 65  // 500KB 초과 시 재시도 품질
    private const val MAX_FILE_SIZE_BYTES = 500 * 1024  // 500 KB

    /**
     * Uri → 임시 파일 (압축 완료)
     * 반환 null = OOM 또는 압축 실패
     * 호출자가 finally { file.delete() } 로 반드시 삭제해야 함
     */
    fun compressForUpload(context: Context, uri: Uri): File? {
        return try {
            val bitmap = loadAndResizeBitmap(context, uri) ?: return null
            val file = createTempJpegFile(context)
            compressBitmapToFile(bitmap, file, DEFAULT_QUALITY)

            // 500KB 초과 시 품질 낮춰서 재압축
            if (file.length() > MAX_FILE_SIZE_BYTES) {
                compressBitmapToFile(bitmap, file, FALLBACK_QUALITY)
            }

            bitmap.recycle()
            file
        } catch (e: OutOfMemoryError) {
            AppLogger.e("ImageCompressUtils", "OOM during compression", e)
            null
        }
    }

    private fun loadAndResizeBitmap(context: Context, uri: Uri): Bitmap? {
        val original = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return null

        val (w, h) = original.width to original.height
        if (w <= MAX_DIMENSION && h <= MAX_DIMENSION) return original

        val scale = MAX_DIMENSION.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(original, (w * scale).toInt(), (h * scale).toInt(), true)
            .also { if (it !== original) original.recycle() }
    }

    private fun createTempJpegFile(context: Context): File =
        File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg").apply { createNewFile() }

    private fun compressBitmapToFile(bitmap: Bitmap, file: File, quality: Int) {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }
}
```

---

## 6. `domain/contracts/MultipartUploadClient.kt`

```kotlin
interface MultipartUploadClient {
    data class UploadResult(val downloadUrl: String, val thumbnailUrl: String)

    suspend fun upload(file: File, path: String): UploadResult
}
```

---

## 7. `data/remote/StorageApi.kt` — Retrofit 정의

```kotlin
interface StorageApi {
    @Multipart
    @POST("api/storage_api.php")
    suspend fun uploadFile(
        @Part file: MultipartBody.Part,
        @Part("action") action: RequestBody,
        @Part("path") path: RequestBody,
        @Part("file_name") fileName: RequestBody,
    ): Response<StorageUploadResponse>
}

// data/dto/storage/StorageUploadResponse.kt
data class StorageUploadResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("download_url") val downloadUrl: String? = null,
    @SerializedName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerializedName("file_name") val fileName: String? = null,
    @SerializedName("error") val error: String? = null,
)
```

---

## 8. `RetrofitMultipartUploadClient.kt` — 구현체

```kotlin
class RetrofitMultipartUploadClient(
    private val storageApi: StorageApi,
) : MultipartUploadClient {

    override suspend fun upload(file: File, path: String): MultipartUploadClient.UploadResult =
        withContext(Dispatchers.IO) {
            // 고유 파일명 생성
            val fileName = "file_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.${file.extension}"

            // Multipart 파트 생성
            val requestFile = file.asRequestBody("image/*".toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", fileName, requestFile)

            // 폼 필드
            val actionBody = ApiActions.UPLOAD_FILE.toRequestBody("text/plain".toMediaTypeOrNull())
            val pathBody = path.toRequestBody("text/plain".toMediaTypeOrNull())
            val fileNameBody = fileName.toRequestBody("text/plain".toMediaTypeOrNull())

            // API 호출
            val response = storageApi.uploadFile(
                file = filePart,
                action = actionBody,
                path = pathBody,
                fileName = fileNameBody,
            )

            if (!response.isSuccessful)
                throw WrapperError.Network(response.code(), response.errorBody()?.string())

            val body = response.body()
            if (body == null || !body.success)
                throw IOException(body?.error ?: "업로드 실패")

            MultipartUploadClient.UploadResult(
                downloadUrl = body.downloadUrl ?: "",
                thumbnailUrl = body.thumbnailUrl ?: body.downloadUrl ?: "",
            )
        }
}
```

---

## 9. `StorageRepository.kt` — 업로드 + 임시 파일 정리

```kotlin
class StorageRepository @Inject constructor(
    private val multipartUploadClient: MultipartUploadClient,
    @ApplicationContext private val context: Context,
) {
    companion object { private const val TAG = "StorageRepository" }

    data class StorageUploadResult(val downloadUrl: String, val thumbnailUrl: String)

    suspend fun uploadFromUri(
        uri: Uri,
        path: String,  // 예: "profile/{uid}", "feed/{feedId}"
    ): Result<StorageUploadResult> {
        val file = ImageCompressUtils.compressForUpload(context, uri)
            ?: return Result.Error(IOException("이미지 압축 실패"))

        return try {
            ApiResponseUtils.safeApiCall(TAG, "uploadFromUri") {
                val uploadResult = multipartUploadClient.upload(file, path)
                StorageUploadResult(
                    downloadUrl = uploadResult.downloadUrl,
                    thumbnailUrl = uploadResult.thumbnailUrl,
                )
            }
        } finally {
            file.delete()  // 성공/실패 무관하게 항상 임시 파일 삭제
        }
    }
}
```

---

## 10. path 명명 규칙

| 용도 | path |
|------|------|
| 프로필 사진 | `"profile/{uid}"` |
| 피드 첨부 이미지 | `"feed/{feedId}"` |
| 채팅 이미지 | `"chat/{chatId}"` |
| 게더링 커버 이미지 | `"gathering/{gatheringId}"` |
| 기타 | `"{domain}/{id}"` |

백엔드에서 이 path로 폴더 구조를 결정함. 프로젝트마다 백엔드와 협의해서 정의.

---

## 11. ViewModel에서 통합 사용 패턴

```kotlin
fun save() {
    viewModelScope.launch {
        _isSaving.value = true
        _error.value = null

        // 텍스트 필드 저장
        authRepository.saveUserField("nickname", _nickname.value.trim())
            .onFailure { _error.value = it.message; _isSaving.value = false; return@launch }

        // 이미지 업로드 (선택한 경우에만)
        _selectedImageUri.value?.let { uri ->
            val uid = sessionPreferences.getUserId() ?: ""
            storageRepository.uploadFromUri(uri, "profile/$uid")
                .onSuccess { result ->
                    authRepository.saveUserField("profileImageUrl", result.downloadUrl)
                }
                .onError { _, message ->
                    _error.value = message
                    _isSaving.value = false
                    return@launch
                }
        }

        _isSaving.value = false
        _saveSuccess.value = true
    }
}
```

---

## 12. 화면에서 이미지 표시 — 선택 전/후 분기

```kotlin
@Composable
fun MyPageEditProfilePhotoPickerOrganism(
    selectedImageUri: Uri?,       // 로컬에서 방금 선택한 Uri
    remoteImageUrl: String?,      // 서버에서 가져온 기존 URL
    onPickClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(AppSizes.profileImageSizeEditProfile.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        // 로컬 Uri 우선, 없으면 서버 URL
        val imageModel: Any? = selectedImageUri ?: remoteImageUrl

        if (imageModel != null) {
            AsyncImage(  // Coil
                model = imageModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        } else {
            // 사진 없음 — 기본 아이콘
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                IconAtom(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    size = AppSizes.iconXlarge.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 카메라 버튼 (우하단)
        IconButtonSmall(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = stringResource(R.string.edit_profile_photo),
            onClick = onPickClick,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .padding(AppSpacing.xs.dp),
        )
    }
}
```

---

## 13. `di/NetworkModule.kt`에 추가

```kotlin
// 업로드 전용 OkHttpClient (타임아웃 30s)
@Provides @Singleton
fun provideStorageApi(
    @UploadOkHttpClient okHttpClient: OkHttpClient,
    gson: Gson,
): StorageApi = Retrofit.Builder()
    .baseUrl(ApiConfig.BASE_URL)
    .client(okHttpClient)
    .addConverterFactory(GsonConverterFactory.create(gson))
    .build()
    .create(StorageApi::class.java)

@Provides @Singleton
fun provideMultipartUploadClient(storageApi: StorageApi): MultipartUploadClient =
    RetrofitMultipartUploadClient(storageApi)
```

---

## 14. `AndroidManifest.xml` 권한 추가

```xml
<!-- Android 12 이하 -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<!-- Android 13+ -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
```

---

## 15. Gradle 의존성

```kotlin
// build.gradle.kts (app)
dependencies {
    // 이미지 크롭 (CropImageContract)
    implementation("com.vanniktech:android-image-cropper:4.5.0")
    // 이미지 로딩 (AsyncImage)
    implementation("io.coil-kt:coil-compose:2.6.0")
}
```

---

## 16. 자주 하는 실수

| 상황 | 잘못 | 올바른 방식 |
|------|------|------------|
| 임시 파일 누수 | `return uploadResult` 후 파일 삭제 안 함 | `finally { file.delete() }` 항상 |
| 원본 업로드 | 갤러리 Uri 그대로 업로드 | `ImageCompressUtils.compressForUpload()` 통해 압축 후 업로드 |
| 파일명 충돌 | `"image.jpg"` 고정 | `file_${timestamp}_${uuid8}.jpg` 고유 이름 |
| 대용량 파일 | 품질 체크 없이 한 번만 압축 | 500KB 초과 시 `FALLBACK_QUALITY(65)`로 재압축 |
| OOM 무시 | `BitmapFactory.decodeStream()` 예외 처리 없음 | `OutOfMemoryError` catch + `null` 반환 |
| 권한 버전 분기 없음 | `READ_EXTERNAL_STORAGE` 단일 사용 | Android 13+ = `READ_MEDIA_IMAGES`, 이하 = `READ_EXTERNAL_STORAGE` |
| 이미지 표시 우선순위 | 서버 URL 먼저 | 로컬 Uri 우선 (방금 선택한 것이 최신) |
| Multipart 타임아웃 | 기본 10s | 업로드용 OkHttpClient에 30s 별도 설정 |

---

*원본 검증 프로젝트: `happy_v12` — `PickImageCropHelper`, `ImageCompressUtils`, `StorageRepository`, `RetrofitMultipartUploadClient`, `StorageApi` 기반*
