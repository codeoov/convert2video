# 네트워크 레이어 구현 가이드

> **목적**: 신규 프로젝트에서 Retrofit + OkHttp 기반 네트워크 레이어를 처음부터 세팅한다.
> `happy_v12`에서 실사용 검증된 패턴 기반.

---

## 1. 전체 구조 개요

```
di/NetworkModule.kt              ← Hilt 싱글턴 제공 (OkHttpClient, Retrofit, API 서비스)
│
├── data/adapters/
│   ├── AuthHeaderInterceptor.kt         ← Bearer 토큰 자동 첨부
│   ├── TokenRefreshHttpInterceptor.kt   ← 401 시 토큰 갱신 + 재시도
│   ├── OkHttpInterceptorAdapter.kt      ← OkHttp ↔ 도메인 타입 변환
│   ├── RetrofitApiClient.kt             ← ApiClient 구현체
│   ├── GsonJsonCodec.kt                 ← JsonCodec 구현체
│   └── RetrofitMultipartUploadClient.kt ← 파일 업로드 클라이언트
│
├── domain/contracts/
│   ├── ApiClient.kt         ← Retrofit 추상화 인터페이스
│   ├── JsonCodec.kt         ← Gson 추상화 인터페이스
│   ├── HttpInterceptor.kt   ← OkHttp 독립 인터셉터 계약
│   ├── HttpRequest.kt       ← 플랫폼 독립 요청 타입
│   ├── HttpResponse.kt      ← 플랫폼 독립 응답 타입
│   └── WrapperError.kt      ← 라이브러리 독립 에러 sealed class
│
├── config/
│   ├── ApiConfig.kt         ← baseUrl (debug/release)
│   ├── ApiTimeouts.kt       ← 타임아웃 상수
│   └── ApiPaging.kt         ← 페이지 크기 상수
│
└── utils/ApiResponseUtils.kt ← safeApiCall() 에러 처리 래퍼
```

---

## 2. 설정 상수

### `config/ApiConfig.kt`
```kotlin
object ApiConfig {
    // debug: HTTP (내부 IP, 같은 Wi-Fi)
    // release: HTTPS (실제 도메인)
    val BASE_URL: String = BuildConfig.API_BASE_URL
}
```

`gradle.properties`:
```properties
# debug
API_BASE_URL=http://192.168.100.15/
# release (CI/CD에서 주입)
API_BASE_URL=https://yourapp.com/
```

### `config/ApiTimeouts.kt`
```kotlin
object ApiTimeouts {
    const val DEFAULT_SECONDS = 10L    // 인증, 단일 항목
    const val LIST_SECONDS = 15L       // 목록 조회
    const val UPLOAD_SECONDS = 30L     // 파일 업로드
}
```

### `config/ApiPaging.kt`
```kotlin
object ApiPaging {
    const val PAGE_SIZE_DEFAULT = 20
    const val PAGE_SIZE_CHAT = 50
}
```

---

## 3. domain/contracts — 외부 라이브러리 추상화

> **왜 필요한가**: ViewModel/Repository가 Retrofit/OkHttp를 직접 import하면
> 라이브러리 버전 업 시 전체 수정 필요. 인터페이스로 격리하면 어댑터 교체만으로 해결.

### `WrapperError.kt`
```kotlin
sealed class WrapperError(message: String? = null, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    data class Network(val code: Int?, override val message: String?) : WrapperError(message)
    data class Parse(override val message: String?) : WrapperError(message)
    data class Permission(override val message: String?) : WrapperError(message)
    data class Sdk(override val message: String?) : WrapperError(message)
}
```

### `ApiClient.kt`
```kotlin
interface ApiClient {
    fun <T : Any> create(serviceClass: Class<T>): T
}
```

### `JsonCodec.kt`
```kotlin
interface JsonCodec {
    fun toJson(obj: Any): String
    fun <T> fromJson(json: String, type: java.lang.reflect.Type): T
}
```

### `HttpInterceptor.kt`, `HttpRequest.kt`, `HttpResponse.kt`
```kotlin
interface HttpInterceptor {
    fun intercept(chain: HttpInterceptorChain): HttpResponse
}

interface HttpInterceptorChain {
    val request: HttpRequest
    fun proceed(request: HttpRequest): HttpResponse
}

data class HttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray?,
)

data class HttpResponse(
    val code: Int,
    val message: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray?,
)
```

---

## 4. 인터셉터 2개

### `AuthHeaderInterceptor.kt` — Bearer 토큰 자동 첨부
```kotlin
@Singleton
class AuthHeaderInterceptor @Inject constructor(
    private val sessionPreferences: SessionPreferences,
) : HttpInterceptor {

    override fun intercept(chain: HttpInterceptorChain): HttpResponse {
        val token = runBlocking { sessionPreferences.getSessionToken() }
        val newRequest = if (token != null) {
            chain.request.copy(
                headers = chain.request.headers + mapOf(
                    "Authorization" to listOf("Bearer $token")
                )
            )
        } else {
            chain.request
        }
        return chain.proceed(newRequest)
    }
}
```

### `TokenRefreshHttpInterceptor.kt` — 401 시 토큰 갱신 + 1회 재시도
```kotlin
@Singleton
class TokenRefreshHttpInterceptor @Inject constructor(
    private val sessionPreferences: SessionPreferences,
    @AuthApiForRefresh private val authApiForRefresh: AuthApi,  // 순환 의존 방지용 별도 클라이언트
) : HttpInterceptor {

    override fun intercept(chain: HttpInterceptorChain): HttpResponse {
        val response = chain.proceed(chain.request)

        // 이미 재시도한 요청이면 그대로 반환
        val alreadyRetried = chain.request.headers["X-Token-Refresh-Retry"]?.firstOrNull() == "1"
        if (response.code != 401 || alreadyRetried) return response

        // 토큰 갱신 시도
        val refreshed = runBlocking {
            runCatching {
                val result = authApiForRefresh.refreshToken(
                    RefreshTokenRequest(refreshToken = sessionPreferences.getRefreshToken())
                )
                if (result.success && result.accessToken != null) {
                    sessionPreferences.setSessionToken(result.accessToken)
                    true
                } else false
            }.getOrDefault(false)
        }

        if (!refreshed) return response

        // 재시도 (재시도 표시 헤더 추가)
        val retryRequest = chain.request.copy(
            headers = chain.request.headers + mapOf("X-Token-Refresh-Retry" to listOf("1"))
        )
        return chain.proceed(retryRequest)
    }
}
```

### `@AuthApiForRefresh` Qualifier
```kotlin
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthApiForRefresh
```

---

## 5. `OkHttpInterceptorAdapter.kt` — OkHttp ↔ 도메인 브릿지

```kotlin
class OkHttpInterceptorAdapter(
    private val domainInterceptor: HttpInterceptor,
) : okhttp3.Interceptor {

    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val domainChain = object : HttpInterceptorChain {
            override val request = chain.request().toDomain()
            override fun proceed(request: HttpRequest) =
                chain.proceed(request.toOkHttp()).toDomain()
        }
        return domainChain.let {
            domainInterceptor.intercept(it).toOkHttp(chain.proceed(chain.request()))
        }
    }

    private fun okhttp3.Request.toDomain() = HttpRequest(
        url = url.toString(),
        method = method,
        headers = headers.toMultimap(),
        body = body?.let { b ->
            val buf = okio.Buffer(); b.writeTo(buf); buf.readByteArray()
        },
    )

    private fun okhttp3.Response.toDomain() = HttpResponse(
        code = code,
        message = message,
        headers = headers.toMultimap(),
        body = body?.bytes(),
    )

    // HttpRequest → OkHttp Request 재구성
    private fun HttpRequest.toOkHttp(): okhttp3.Request { /* 헤더 재구성 */ }
    // HttpResponse → OkHttp Response 재구성
    private fun HttpResponse.toOkHttp(original: okhttp3.Response): okhttp3.Response { /* 응답 재구성 */ }
}
```

---

## 6. `di/NetworkModule.kt` — Hilt 모듈

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // --- Gson / JsonCodec ---
    @Provides @Singleton
    fun provideGson(): Gson = GsonBuilder().setLenient().create()

    @Provides @Singleton
    fun provideJsonCodec(gson: Gson): JsonCodec = GsonJsonCodec(gson)

    // --- 메인 OkHttpClient (토큰 갱신 + 인증 헤더 + 로깅) ---
    @Provides @Singleton
    fun provideOkHttpClient(
        authHeaderInterceptor: AuthHeaderInterceptor,
        tokenRefreshInterceptor: TokenRefreshHttpInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(ApiTimeouts.DEFAULT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(ApiTimeouts.DEFAULT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(ApiTimeouts.DEFAULT_SECONDS, TimeUnit.SECONDS)
        // 순서 중요: TokenRefresh → AuthHeader → logging
        .addInterceptor(OkHttpInterceptorAdapter(tokenRefreshInterceptor))
        .addInterceptor(OkHttpInterceptorAdapter(authHeaderInterceptor))
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    // --- 토큰 갱신 전용 클라이언트 (TokenRefreshInterceptor 제외 → 순환 방지) ---
    @Provides @Singleton @AuthApiForRefresh
    fun provideOkHttpClientForRefresh(
        authHeaderInterceptor: AuthHeaderInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(ApiTimeouts.DEFAULT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(ApiTimeouts.DEFAULT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(ApiTimeouts.DEFAULT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(OkHttpInterceptorAdapter(authHeaderInterceptor))  // TokenRefresh 없음
        .build()

    // --- 업로드 전용 클라이언트 (타임아웃 길게) ---
    @Provides @Singleton
    fun provideMultipartOkHttpClient(
        authHeaderInterceptor: AuthHeaderInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(ApiTimeouts.UPLOAD_SECONDS, TimeUnit.SECONDS)
        .readTimeout(ApiTimeouts.UPLOAD_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(ApiTimeouts.UPLOAD_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(OkHttpInterceptorAdapter(authHeaderInterceptor))
        .build()

    // --- Retrofit (메인) ---
    @Provides @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    // --- ApiClient (도메인 추상화) ---
    @Provides @Singleton
    fun provideApiClient(retrofit: Retrofit): ApiClient = RetrofitApiClient(retrofit)

    // --- API 서비스 인스턴스 (도메인마다 추가) ---
    @Provides @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides @Singleton
    fun provideGatheringApi(retrofit: Retrofit): GatheringApi =
        retrofit.create(GatheringApi::class.java)

    // ... 새 도메인 추가 시 동일 패턴으로 추가

    // --- Multipart Upload Client ---
    @Provides @Singleton
    fun provideMultipartUploadClient(
        @AuthApiForRefresh okHttpClient: OkHttpClient,  // 업로드용 OkHttpClient 사용
        gson: Gson,
    ): MultipartUploadClient = RetrofitMultipartUploadClient(
        baseUrl = ApiConfig.BASE_URL,
        okHttpClient = okHttpClient,
        gson = gson,
    )

    // --- 토큰 갱신용 AuthApi (별도 클라이언트로 생성) ---
    @Provides @Singleton @AuthApiForRefresh
    fun provideAuthApiForRefresh(@AuthApiForRefresh okHttpClient: OkHttpClient, gson: Gson): AuthApi =
        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(AuthApi::class.java)
}
```

---

## 7. `utils/ApiResponseUtils.kt` — 에러 처리 래퍼

모든 Repository 메서드는 이 함수로 래핑한다.

```kotlin
object ApiResponseUtils {

    suspend inline fun <T> safeApiCall(
        tag: String,
        method: String,
        noinline errorMapper: ((Throwable) -> String?)? = null,
        block: suspend () -> T,
    ): Result<T> = runCatching { block() }.fold(
        onSuccess = { Result.Success(it) },
        onFailure = { t ->
            AppLogger.e(tag, "$method 실패", t)  // 항상 로그 (release 포함)
            val msg = errorMapper?.invoke(t) ?: translateThrowable(t)
            Result.Error(t, msg)
        },
    )

    fun translateThrowable(t: Throwable): String = when (t) {
        is WrapperError.Network -> t.message?.takeIf { it.isNotBlank() } ?: "HTTP ${t.code ?: 0}"
        is WrapperError.Parse -> "응답 파싱 오류"
        is java.io.IOException -> t.message ?: "네트워크 연결 오류"
        else -> t.message ?: "알 수 없는 오류"
    }
}
```

### Repository에서 사용 패턴
```kotlin
suspend fun loadItems(limit: Int, offset: Int): Result<ListResult> =
    ApiResponseUtils.safeApiCall(TAG, "loadItems") {
        val response = itemApi.listItems(limit = limit, offset = offset)

        // 응답 검증 (필수)
        if (!response.isSuccessful)
            throw WrapperError.Network(response.code(), response.errorBody()?.string())

        val body = response.body()
        if (body == null || body.success != true)
            throw IOException(body?.error ?: "Unknown error")

        ListResult(
            items = body.items ?: emptyList(),
            hasMore = body.hasMore ?: false,
        )
    }
```

---

## 8. Retrofit API 인터페이스 정의 패턴

```kotlin
// data/remote/ItemApi.kt
interface ItemApi {

    // GET 목록 조회 (query param 방식 — PHP 백엔드 공통)
    @GET("api/item_api.php")
    suspend fun listItems(
        @Query("action") action: String = ApiActions.LIST_ITEMS,
        @Query("limit") limit: Int = ApiPaging.PAGE_SIZE_DEFAULT,
        @Query("offset") offset: Int = 0,
        @Query("keyword") keyword: String? = null,
    ): Response<ListItemsResponse>

    // GET 단건 조회
    @GET("api/item_api.php")
    suspend fun getItem(
        @Query("action") action: String = ApiActions.GET_ITEM,
        @Query("id") id: Int,
    ): Response<GetItemResponse>

    // POST (JSON body)
    @POST("api/item_api.php")
    suspend fun createItem(
        @Body request: CreateItemRequest,
    ): Response<CreateItemResponse>
}
```

---

## 9. Hilt 바인딩 — 신규 도메인 추가 체크리스트

```
□ data/remote/XxxApi.kt                  ← Retrofit 인터페이스 정의
□ data/repository/XxxRepository.kt       ← safeApiCall로 구현
□ domain/contracts/XxxRepository.kt      ← 인터페이스 (선택, 규모 클 때)
□ di/NetworkModule.kt                    ← provideXxxApi() 추가
□ di/RepositoryModule.kt                 ← @Binds XxxRepository → XxxRepositoryImpl
□ config/ApiActions.kt                   ← 새 action 상수 추가
```

---

## 10. 자주 하는 실수

| 상황 | 잘못 | 올바른 방식 |
|------|------|------------|
| 토큰 갱신 클라이언트 | 메인 OkHttpClient 사용 | `@AuthApiForRefresh` 별도 클라이언트 |
| 인터셉터 순서 | AuthHeader → TokenRefresh | TokenRefresh → AuthHeader (갱신 후 헤더 재첨부) |
| Retrofit 직접 import | Repository에서 `Retrofit.create()` | `ApiClient.create()` 경유 |
| OkHttp 직접 import | 인터셉터에서 `okhttp3.*` | `domain/contracts/HttpInterceptor` 구현 |
| 에러 무시 | `response.body()` 바로 사용 | `isSuccessful` + `body != null` 이중 검증 |
| 타임아웃 미설정 | 기본값 사용 | `ApiTimeouts.*` 명시적 설정 |
| 로그 노출 | release에 BODY 레벨 | `BuildConfig.DEBUG` 분기 필수 |

---

*원본 검증 프로젝트: `happy_v12` — `di/NetworkModule.kt`, `data/adapters/`, `domain/contracts/` 기반*
