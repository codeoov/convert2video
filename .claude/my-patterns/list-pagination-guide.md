# 리스트 화면 + 페이지네이션 구현 가이드

> **목적**: 신규 프로젝트에서 목록 조회 화면(무한 스크롤, 에러/로딩/빈 상태)을 처음부터 구현한다.
> `happy_v12`에서 실사용 검증된 패턴 기반.
>
> **convert2video 적용 범위**: 이 문서의 §8 상태 분기(로딩/에러/빈 상태 3분기 UI 패턴)만 참고 가치가 있음.
> §2~7, §9~11(Retrofit/Repository/오프셋·페이지네이션·무한스크롤·Pull-to-Refresh)는 convert2video에
> 백엔드·네트워크 레이어가 없어 해당 없음.

---

## 1. 전체 흐름 개요

```
Screen (5_pages)
  └→ collectAsState(uiState)
       └→ 상태 분기 (로딩 / 에러 / 빈 / 목록)
            └→ LazyColumn
                 └→ 항목 카드 + 하단 loadMore 트리거

ViewModel (controllers/)
  ├── loadItems()       ← 초기 로드 (offset=0)
  └── loadMoreItems()   ← 추가 로드 (offset=현재개수)

Repository (data/repository/)
  └── loadItems(limit, offset, ...필터) → Result<ListResult>

API (data/remote/)
  └── listItems(@Query limit, offset, ...) → Response<ListResponse>
```

---

## 2. `Result<T>` sealed class

> **DEPRECATED / convert2video**: 원격 `Result`·네트워크 에러 래퍼 패턴 — convert2video에 해당 없음. 본문은 참고 보관용(삭제하지 않음).

모든 Repository 반환 타입. 에러 처리를 강제하는 타입 안전 래퍼.

```kotlin
// domain/enums/Result.kt
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(
        val exception: Throwable,
        val message: String? = null,
    ) : Result<Nothing>()
}

// 확장 함수
inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> {
    if (this is Result.Success) action(data)
    return this
}

inline fun <T> Result<T>.onError(action: (Throwable, String?) -> Unit): Result<T> {
    if (this is Result.Error) action(exception, message)
    return this
}
```

---

## 3. Retrofit API 정의

> **DEPRECATED / convert2video**: Retrofit/OkHttp API 레이어 — convert2video에 해당 없음(네트워크 백엔드 없음). 본문은 참고 보관용.

```kotlin
// data/remote/ItemApi.kt
interface ItemApi {
    @GET("api/item_api.php")
    suspend fun listItems(
        @Query("action") action: String = ApiActions.LIST_ITEMS,
        @Query("limit") limit: Int = ApiPaging.PAGE_SIZE_DEFAULT,
        @Query("offset") offset: Int = 0,
        @Query("keyword") keyword: String? = null,
        @Query("category") category: String? = null,
        @Query("order_by") orderBy: String? = null,
    ): Response<ListItemsResponse>
}

// data/dto/item/ListItemsResponse.kt
data class ListItemsResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("items") val items: List<ItemDto>? = null,
    @SerializedName("has_more") val hasMore: Boolean? = null,  // 백엔드가 더 있음을 알림
    @SerializedName("error") val error: String? = null,
)
```

---

## 4. Repository 구현

> **DEPRECATED / convert2video**: Retrofit Repository·원격 페이지네이션 — convert2video에 해당 없음. 본문은 참고 보관용(삭제하지 않음).

페이지네이션 **상태(offset)는 Repository에 없다** — ViewModel이 관리.

```kotlin
// data/repository/ItemRepository.kt
class ItemRepository @Inject constructor(
    private val itemApi: ItemApi,
    @ApplicationContext private val context: Context,
) {
    companion object { private const val TAG = "ItemRepository" }

    data class ListItemsResult(
        val items: List<ItemDto>,
        val hasMore: Boolean,
    )

    suspend fun loadItems(
        limit: Int = ApiPaging.PAGE_SIZE_DEFAULT,
        offset: Int = 0,
        keyword: String? = null,
        category: String? = null,
    ): Result<ListItemsResult> = ApiResponseUtils.safeApiCall(TAG, "loadItems") {
        val response = itemApi.listItems(
            limit = limit,
            offset = offset,
            keyword = keyword,
            category = category,
        )
        if (!response.isSuccessful)
            throw WrapperError.Network(response.code(), response.errorBody()?.string())

        val body = response.body()
        if (body == null || !body.success)
            throw IOException(body?.error ?: "목록 조회 실패")

        ListItemsResult(
            items = body.items ?: emptyList(),
            hasMore = body.hasMore ?: false,
        )
    }
}
```

---

## 5. UiState 설계

> **DEPRECATED / convert2video**: 원격 목록 offset/hasMore UiState — convert2video에 해당 없음. 본문은 참고 보관용(삭제하지 않음).

```kotlin
// controllers/xxx/ItemListViewModel.kt 에 포함
data class ItemListUiState(
    // 목록 데이터
    val items: List<ItemDto> = emptyList(),

    // 초기 로딩 (목록이 비어 있는 상태)
    val isLoading: Boolean = false,

    // 추가 로딩 (기존 목록 아래에 더 불러오는 중)
    val isLoadingMore: Boolean = false,

    // 더 불러올 항목이 있는지
    val hasMore: Boolean = true,

    // 초기 로딩 에러
    val error: String? = null,

    // 필터 상태 (도메인에 따라 추가)
    val selectedCategory: String? = null,
    val keyword: String = "",
)
```

---

## 6. ViewModel — 오프셋 페이지네이션 (무한 스크롤)

> **DEPRECATED / convert2video**: Hilt+Repository 오프셋 무한스크롤 — convert2video에 해당 없음. 본문은 참고 보관용(삭제하지 않음).

```kotlin
@HiltViewModel
class ItemListViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ItemListUiState())
    val uiState: StateFlow<ItemListUiState> = _uiState.asStateFlow()

    init { loadItems() }

    // 초기 로드 (새로고침 포함)
    fun loadItems(forceRefresh: Boolean = false) {
        if (_uiState.value.isLoading && !forceRefresh) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            itemRepository.loadItems(
                limit = ApiPaging.PAGE_SIZE_DEFAULT,
                offset = 0,
                keyword = _uiState.value.keyword.ifBlank { null },
                category = _uiState.value.selectedCategory,
            ).onSuccess { result ->
                _uiState.update {
                    it.copy(
                        items = result.items,
                        hasMore = result.hasMore,
                        isLoading = false,
                    )
                }
            }.onError { _, message ->
                _uiState.update { it.copy(isLoading = false, error = message) }
            }
        }
    }

    // 추가 로드 (무한 스크롤)
    fun loadMoreItems() {
        val state = _uiState.value
        // 동시 로딩 방지 + 더 없으면 중단
        if (state.isLoadingMore || !state.hasMore || state.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            itemRepository.loadItems(
                limit = ApiPaging.PAGE_SIZE_DEFAULT,
                offset = state.items.size,  // 현재 목록 크기 = 다음 시작점
                keyword = state.keyword.ifBlank { null },
                category = state.selectedCategory,
            ).onSuccess { result ->
                _uiState.update {
                    it.copy(
                        items = it.items + result.items,  // 기존 목록에 추가
                        hasMore = result.hasMore,
                        isLoadingMore = false,
                    )
                }
            }.onError { _, _ ->
                _uiState.update { it.copy(isLoadingMore = false) }
                // 추가 로드 실패는 조용히 (초기 로드 실패와 달리 에러 표시 안 함)
            }
        }
    }

    // 필터 변경 시 목록 리셋
    fun onCategorySelected(category: String?) {
        _uiState.update { it.copy(selectedCategory = category) }
        loadItems()
    }

    fun onKeywordChanged(keyword: String) {
        _uiState.update { it.copy(keyword = keyword) }
    }

    fun clearError() { _uiState.update { it.copy(error = null) } }
}
```

---

## 7. ViewModel — 클라이언트 사이드 더보기 (섹션별 카드)

> **DEPRECATED / convert2video**: Hilt 섹션형 더보기 ViewModel — convert2video에 해당 없음. 본문은 참고 보관용(삭제하지 않음).

홈 화면처럼 이미 전체 데이터를 받았고, 화면에 순차적으로 보여주는 방식.

```kotlin
@HiltViewModel
class SectionMoreViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SectionMoreUiState())
    val uiState: StateFlow<SectionMoreUiState> = _uiState.asStateFlow()

    // 처음엔 INITIAL_VISIBLE_COUNT(예: 6)개만 보임
    // 더보기 누를 때마다 STEP(예: 6)씩 증가
    fun expandVisibleList() {
        val max = _uiState.value.allItems.size
        _uiState.update { state ->
            state.copy(visibleCount = minOf(state.visibleCount + VISIBLE_STEP, max))
        }
    }
}

data class SectionMoreUiState(
    val allItems: List<ItemDto> = emptyList(),
    val visibleCount: Int = INITIAL_VISIBLE_COUNT,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val displayedItems: List<ItemDto> get() = allItems.take(visibleCount)
    val showLoadMoreButton: Boolean get() = allItems.size > visibleCount
}

private const val INITIAL_VISIBLE_COUNT = 6
private const val VISIBLE_STEP = 6
```

---

## 8. Screen — 상태 분기 + LazyColumn 패턴

```kotlin
@Composable
fun ItemListScreen(
    onItemClick: (Int) -> Unit,
    onBack: () -> Unit,
    viewModel: ItemListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val items = uiState.items

    ScaffoldTemplate(
        topBar = { /* 상단 바 */ },
        snackbarHostState = snackbarHostState,
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

            // ── 상태 분기 ──────────────────────────────────────────
            when {
                // 1) 초기 로딩 중
                uiState.isLoading && items.isEmpty() -> {
                    AppLoadingIndicatorMolecule(modifier = Modifier.align(Alignment.Center))
                }

                // 2) 초기 로딩 에러
                uiState.error != null && items.isEmpty() -> {
                    ErrorRetryBlockMolecule(
                        message = uiState.error,
                        onRetry = { viewModel.loadItems() },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                // 3) 빈 상태
                items.isEmpty() -> {
                    EmptyStateViewMolecule(
                        message = stringResource(R.string.empty_items),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                // 4) 목록 표시
                else -> {
                    ItemLazyColumn(
                        items = items,
                        isLoadingMore = uiState.isLoadingMore,
                        hasMore = uiState.hasMore,
                        onItemClick = onItemClick,
                        onLoadMore = { viewModel.loadMoreItems() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemLazyColumn(
    items: List<ItemDto>,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onItemClick: (Int) -> Unit,
    onLoadMore: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(AppSpacing.md.dp),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm.dp),
    ) {
        items(
            items = items,
            key = { it.id },  // 안정적인 key로 리컴포지션 최소화
        ) { item ->
            ItemCardMolecule(
                item = item,
                onClick = { onItemClick(item.id) },
            )
        }

        // 하단 추가 로딩 인디케이터
        if (isLoadingMore) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(AppSpacing.md.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppLoadingIndicatorMolecule()
                }
            }
        }

        // 무한 스크롤 자동 트리거: 마지막 항목에 도달하면 loadMore 호출
        if (hasMore && !isLoadingMore) {
            item {
                LaunchedEffect(Unit) { onLoadMore() }
            }
        }
    }
}
```

---

## 9. Pull-to-Refresh 추가 (선택)

> **DEPRECATED / convert2video**: Material3 Pull-to-Refresh + 원격 재로드 — convert2video에 해당 없음. 본문은 참고 보관용(삭제하지 않음).

```kotlin
// PullRefreshState (Compose Material3)
val pullRefreshState = rememberPullToRefreshState()

if (pullRefreshState.isRefreshing) {
    LaunchedEffect(Unit) {
        viewModel.loadItems(forceRefresh = true)
        pullRefreshState.endRefresh()
    }
}

Box(modifier = Modifier.nestedScroll(pullRefreshState.nestedScrollConnection)) {
    LazyColumn { /* ... */ }
    PullToRefreshContainer(state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter))
}
```

---

## 10. 섹션형 홈 화면 — 여러 목록을 하나의 LazyColumn에

> **DEPRECATED / convert2video**: Route/NavController 섹션형 홈 — convert2video에 해당 없음. 본문은 참고 보관용(삭제하지 않음).

홈 화면처럼 "배너 + 추천 섹션 + 인기 섹션 + 신규 섹션"을 하나의 LazyColumn으로.

```kotlin
LazyColumn {
    // 배너
    item { BannerSliderOrganism(banners = uiState.banners) }

    // 추천 섹션
    item {
        HorizontalCardListSectionScaffold(
            title = stringResource(R.string.section_recommended),
            items = uiState.recommendedItems,
            onSeeMore = { navController.navigate(Route.RECOMMENDED_MORE) },
        ) { item -> ItemCardMolecule(item) }
    }

    // 인기 섹션
    item {
        VerticalCardListSectionScaffold(
            title = stringResource(R.string.section_popular),
            items = uiState.popularItems.take(3),  // 홈에선 3개만
            onSeeMore = { navController.navigate(Route.POPULAR_MORE) },
        ) { item -> ItemCardMolecule(item) }
    }

    // 각 섹션은 독립 로딩 상태 (isRecommendedLoading, isPopularLoading)
}
```

---

## 11. 필터/검색과 페이지네이션 연동

> **DEPRECATED / convert2video**: debounce 검색+원격 페이지네이션 연동 — convert2video에 해당 없음. 본문은 참고 보관용(삭제하지 않음).

```kotlin
// 검색어 입력 시 debounce 후 재로드
var searchJob: Job? = null
fun onSearch(keyword: String) {
    _uiState.update { it.copy(keyword = keyword) }
    searchJob?.cancel()
    searchJob = viewModelScope.launch {
        delay(DEBOUNCE_SEARCH_MS)  // 400ms
        loadItems()  // 내부에서 offset=0 리셋
    }
}

// 필터 변경 시 즉시 재로드
fun onFilterChanged(category: String?) {
    _uiState.update { it.copy(selectedCategory = category) }
    loadItems()
}
```

---

## 12. 자주 하는 실수

| 상황 | 잘못 | 올바른 방식 |
|------|------|------------|
| offset 계산 | `page * pageSize` | `items.size` (현재 목록 크기가 곧 offset) |
| 추가 로드 중복 | 연속 스크롤로 중복 호출 | `if (isLoadingMore \|\| !hasMore) return` 가드 |
| 목록 교체 vs 추가 | 초기 로드도 append | 초기 로드: `items = result`, 추가: `items = items + result` |
| key 누락 | `items(items) { item -> }` | `items(items, key = { it.id }) { }` — 애니메이션 최적화 |
| 필터 후 offset 미리셋 | 필터 변경 후 이전 offset 유지 | 필터/검색 변경 시 반드시 `offset=0`으로 리셋 (= `loadItems()` 재호출) |
| 에러 중복 표시 | 추가 로드 실패도 error 표시 | 추가 로드 실패는 조용히 처리, 초기 로드 실패만 ErrorRetryBlock |
| `hasMore` 초기값 | `false`로 시작 | `true`로 시작 (첫 로드 전엔 "있을 수 있음"으로 가정) |

---

*원본 검증 프로젝트: `happy_v12` — `HomeTabViewModel`, `VerticalCardMoreViewModel`, `GatheringRepository`, `GatheringListUiState` 기반*
