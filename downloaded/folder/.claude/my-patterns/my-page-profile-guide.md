# 마이페이지 / 프로필 구현 가이드

> **목적**: 신규 프로젝트에서 탭 기반 마이페이지와 프로필 편집 화면을 처음부터 구현한다.
> `happy_v12`에서 실사용 검증된 패턴 기반.

---

## 1. 전체 구조 개요

```
MyPageTabContent (5_pages) — 진입점, ViewModel 주입
  └→ MyPageTabTemplate (4_templates) — 탭 선택에 따라 Organism 분기
       ├→ TAB_PROFILE  → MyPageProfileSectionOrganism
       │                   └→ MyPageHeaderOrganism (아바타 + 닉네임 + 메타)
       │                   └→ ActionButtonRowMolecule (프로필 편집 버튼)
       ├→ TAB_ACTIVITY → MyPageMenuSectionOrganism (items=ACTIVITY_ITEMS)
       ├→ TAB_INVITE   → MyPageMenuSectionOrganism (items=INVITE_ITEMS)
       ├→ TAB_SUPPORT  → MyPageMenuSectionOrganism (items=SUPPORT_ITEMS)
       └→ TAB_ACCOUNT  → MyPageAccountSectionOrganism

EditProfileScreen (5_pages) — 별도 화면
  └→ MyPageSettingsFormTemplate (4_templates)
       ├→ MyPageEditProfilePhotoPickerOrganism (이미지 선택)
       ├→ TextFieldAtom × N (닉네임, 생년월일, 자기소개)
       ├→ MyPageEditProfileGenderSelectorOrganism (칩 선택)
       └→ MyPageEditProfileVisibilityOrganism (공개 범위 칩)
```

---

## 2. 파일 구조

```
ui/views/
├── 5_pages/member_5_mypage/
│   ├── MyPageTabContent.kt         ← 진입점 (탭 상태 + VM 주입)
│   ├── EditProfileScreen.kt        ← 프로필 편집
│   ├── ChangeEmailScreen.kt
│   ├── ChangePhoneScreen.kt
│   ├── LanguageSettingsScreen.kt
│   ├── LoginHistoryScreen.kt
│   └── ...
├── 4_templates/member_5_mypage/
│   ├── MyPageTabTemplate.kt        ← 탭 분기 레이아웃
│   └── MyPageSettingsFormTemplate.kt ← 설정 폼 레이아웃
└── 3_organisms/member_5_mypage/
    ├── MyPageHeaderOrganism.kt
    ├── MyPageProfileSectionOrganism.kt
    ├── MyPageMenuSectionOrganism.kt
    ├── MyPageListCardOrganism.kt
    ├── MyPageTabsOrganism.kt
    ├── MyPageAccountSectionOrganism.kt
    ├── MyPageEditProfilePhotoPickerOrganism.kt
    ├── MyPageEditProfileGenderSelectorOrganism.kt
    └── MyPageEditProfileVisibilityOrganism.kt

controllers/member/my_page/
├── ProfileViewModel.kt             ← 프로필 읽기 전용
└── EditProfileViewModel.kt         ← 프로필 편집

config/Route.kt                     ← MY_PAGE_TAB, EDIT_PROFILE, ... 상수
```

---

## 3. 탭 아이템 데이터 구조

```kotlin
// 탭 메뉴 아이템 모델
data class MyPageTabItem(
    val labelResId: Int,
    val route: String?,          // null = 특수 액션 (탈퇴, 로그아웃 등)
    val iconResId: Int? = null,
    val badgeCount: Int? = null, // 알림 배지 (선택)
)

// 탭 정의 + 아이템 목록
object MyPageTabItems {
    // 탭 인덱스 상수
    const val TAB_PROFILE = 0
    const val TAB_ACTIVITY = 1
    const val TAB_INVITE = 2
    const val TAB_SUPPORT = 3
    const val TAB_ACCOUNT = 4

    // 탭 라벨 리소스
    val TAB_LABELS = listOf(
        R.string.my_page_tab_profile,
        R.string.my_page_tab_activity,
        R.string.my_page_tab_invite,
        R.string.my_page_tab_support,
        R.string.my_page_tab_account,
    )

    // 각 탭의 메뉴 아이템
    val ACTIVITY_ITEMS = listOf(
        MyPageTabItem(R.string.my_page_my_bookings, Route.MY_BOOKINGS),
        MyPageTabItem(R.string.my_page_wishlist, Route.WISHLIST),
        MyPageTabItem(R.string.my_page_my_reviews, Route.MY_REVIEWS),
    )

    val INVITE_ITEMS = listOf(
        MyPageTabItem(R.string.my_page_invite_friends, Route.FRIEND_INVITE),
    )

    val SUPPORT_ITEMS = listOf(
        MyPageTabItem(R.string.my_page_dispute_tickets, Route.DISPUTE_TICKETS),
        MyPageTabItem(R.string.my_page_faq, Route.FAQ),
    )

    // 계정 탭은 상태에 따라 동적 생성
    fun getAccountItems(
        emailVerified: Boolean,
        phoneVerified: Boolean,
        isAdmin: Boolean,
    ): List<MyPageTabItem> = buildList {
        add(MyPageTabItem(R.string.my_page_change_email, Route.CHANGE_EMAIL))
        add(MyPageTabItem(R.string.my_page_change_phone, Route.CHANGE_PHONE))
        add(MyPageTabItem(R.string.my_page_language, Route.LANGUAGE_SETTINGS))
        add(MyPageTabItem(R.string.my_page_privacy, Route.PRIVACY_SETTINGS))
        add(MyPageTabItem(R.string.my_page_login_history, Route.LOGIN_HISTORY))
        if (isAdmin) add(MyPageTabItem(R.string.my_page_admin, Route.ADMIN_DASHBOARD))
        add(MyPageTabItem(R.string.my_page_logout, route = null))        // 특수 액션
        add(MyPageTabItem(R.string.my_page_withdraw, route = null))      // 특수 액션
    }
}
```

---

## 4. `MyPageTabContent.kt` — 진입점

```kotlin
@Composable
fun MyPageTabContent(
    navController: NavController? = null,
    profileViewModel: ProfileViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val profile by profileViewModel.profile.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(MyPageTabItems.TAB_PROFILE) }
    var showWithdrawConfirm by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { profileViewModel.loadProfile() }

    // 특수 액션 처리: route == null 인 메뉴 아이템
    val onItemTap: (MyPageTabItem) -> Unit = { item ->
        when {
            item.route != null -> navController?.navigate(item.route)
            item.labelResId == R.string.my_page_withdraw -> showWithdrawConfirm = true
            item.labelResId == R.string.my_page_logout -> showLogoutConfirm = true
        }
    }

    MyPageTabTemplate(
        selectedTab = selectedTab,
        profile = profile,
        onTabSelected = { selectedTab = it },
        onEditProfile = { navController?.navigate(Route.EDIT_PROFILE) },
        onItemTap = onItemTap,
    )

    // 탈퇴 확인 다이얼로그
    if (showWithdrawConfirm) {
        ConfirmDialogMolecule(
            title = stringResource(R.string.my_page_withdraw_confirm_title),
            message = stringResource(R.string.my_page_withdraw_confirm_message),
            onConfirm = {
                showWithdrawConfirm = false
                authViewModel.withdrawAccount()
            },
            onDismiss = { showWithdrawConfirm = false },
        )
    }

    // 로그아웃 확인 다이얼로그
    if (showLogoutConfirm) {
        ConfirmDialogMolecule(
            title = stringResource(R.string.my_page_logout_confirm_title),
            onConfirm = {
                showLogoutConfirm = false
                authViewModel.signOut()
                navController?.navigate(Route.WELCOME) {
                    popUpTo(Route.HOME) { inclusive = true }
                }
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }
}
```

---

## 5. `MyPageTabTemplate.kt` — 탭 분기

```kotlin
@Composable
fun MyPageTabTemplate(
    selectedTab: Int,
    profile: UserProfileDto?,
    onTabSelected: (Int) -> Unit,
    onEditProfile: () -> Unit,
    onItemTap: (MyPageTabItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 탭 바
        MyPageTabsOrganism(
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
        )

        HorizontalDividerAtom()

        // 탭 내용
        when (selectedTab) {
            MyPageTabItems.TAB_PROFILE -> MyPageProfileSectionOrganism(
                profile = profile,
                onEditProfile = onEditProfile,
            )
            MyPageTabItems.TAB_ACTIVITY -> MyPageMenuSectionOrganism(
                items = MyPageTabItems.ACTIVITY_ITEMS,
                onItemTap = onItemTap,
            )
            MyPageTabItems.TAB_INVITE -> MyPageMenuSectionOrganism(
                items = MyPageTabItems.INVITE_ITEMS,
                onItemTap = onItemTap,
            )
            MyPageTabItems.TAB_SUPPORT -> MyPageMenuSectionOrganism(
                items = MyPageTabItems.SUPPORT_ITEMS,
                onItemTap = onItemTap,
            )
            MyPageTabItems.TAB_ACCOUNT -> MyPageAccountSectionOrganism(
                profile = profile,
                onItemTap = onItemTap,
            )
        }
    }
}
```

---

## 6. Organism 조합 패턴

### `MyPageHeaderOrganism` — 프로필 헤더
```kotlin
@Composable
fun MyPageHeaderOrganism(
    profile: UserProfileDto?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(AppSpacing.lg.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProfileAvatarMolecule(
            imageUrl = profile?.profileImageUrl,
            size = AppSizes.profileImageSizeMyPage.dp,
        )
        Spacer(Modifier.height(AppSpacing.sm.dp))
        Text5(
            text = profile?.nickname ?: stringResource(R.string.my_page_anonymous),
            fontWeight = FontWeight.Bold,
        )
        if (!profile?.selfIntroduction.isNullOrBlank()) {
            Spacer(Modifier.height(AppSpacing.xs.dp))
            Text7(
                text = profile!!.selfIntroduction!!,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
```

### `MyPageListCardOrganism` — 메뉴 카드 (Divider 포함)
```kotlin
@Composable
fun MyPageListCardOrganism(
    items: List<MyPageTabItem>,
    onItemTap: (MyPageTabItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSizes.listCardBorderRadius.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = AppSizes.cardElevationDefault.dp),
    ) {
        Column {
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDividerAtom()
                MenuItemRowMolecule(
                    labelResId = item.labelResId,
                    badgeCount = item.badgeCount,
                    onClick = { onItemTap(item) },
                )
            }
        }
    }
}
```

### `MyPageMenuSectionOrganism` — 섹션 (빈 상태 포함)
```kotlin
@Composable
fun MyPageMenuSectionOrganism(
    items: List<MyPageTabItem>,
    onItemTap: (MyPageTabItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        EmptyStateViewMolecule(
            message = stringResource(R.string.empty_menu),
            modifier = modifier.padding(AppSpacing.xl.dp),
        )
    } else {
        MyPageListCardOrganism(
            items = items,
            onItemTap = onItemTap,
            modifier = modifier.padding(AppSpacing.md.dp),
        )
    }
}
```

---

## 7. `ProfileViewModel` — 읽기 전용

```kotlin
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _profile = MutableStateFlow<UserProfileDto?>(null)
    val profile: StateFlow<UserProfileDto?> = _profile.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadProfile() {
        viewModelScope.launch {
            _isLoading.value = true
            profileRepository.loadProfile()
                .onSuccess { _profile.value = it }
                .onError { _, _ -> /* 조용히 실패 — 캐시된 세션 데이터 사용 */ }
            _isLoading.value = false
        }
    }
}
```

---

## 8. `EditProfileViewModel` — 편집 전용

```kotlin
@HiltViewModel
class EditProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // 각 필드를 독립 StateFlow로 관리 (UI에서 개별 collect 가능)
    private val _nickname = MutableStateFlow("")
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    private val _birthDate = MutableStateFlow("")
    val birthDate: StateFlow<String> = _birthDate.asStateFlow()

    private val _gender = MutableStateFlow<String?>(null)
    val gender: StateFlow<String?> = _gender.asStateFlow()

    private val _selfIntroduction = MutableStateFlow("")
    val selfIntroduction: StateFlow<String> = _selfIntroduction.asStateFlow()

    private val _profileVisibility = MutableStateFlow(PROFILE_VISIBILITY_PUBLIC)
    val profileVisibility: StateFlow<String> = _profileVisibility.asStateFlow()

    private val _selectedImageUri = MutableStateFlow<android.net.Uri?>(null)
    val selectedImageUri: StateFlow<android.net.Uri?> = _selectedImageUri.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess.asStateFlow()

    // 기존 프로필 데이터로 초기값 세팅 (EditProfileScreen의 LaunchedEffect에서 호출)
    fun loadProfile(profile: UserProfileDto) {
        _nickname.value = profile.nickname ?: ""
        _birthDate.value = profile.birthDate ?: ""
        _gender.value = profile.gender
        _selfIntroduction.value = profile.selfIntroduction ?: ""
        _profileVisibility.value = profile.profileVisibility ?: PROFILE_VISIBILITY_PUBLIC
    }

    // 필드 세터
    fun setNickname(value: String) { _nickname.value = value }
    fun setBirthDate(value: String) { _birthDate.value = value }
    fun setGender(value: String?) { _gender.value = value }
    fun setSelfIntroduction(value: String) { _selfIntroduction.value = value }
    fun setProfileVisibility(value: String) { _profileVisibility.value = value }
    fun setSelectedImageUri(uri: android.net.Uri?) { _selectedImageUri.value = uri }

    fun save() {
        val nickname = _nickname.value.trim()
        if (nickname.isBlank()) {
            _error.value = context.getString(R.string.validator_nickname_empty)
            return
        }
        viewModelScope.launch {
            _isSaving.value = true
            _error.value = null
            profileRepository.saveProfile(
                nickname = nickname,
                birthDate = _birthDate.value.trim(),
                gender = _gender.value,
                selfIntroduction = _selfIntroduction.value.trim(),
                profileVisibility = _profileVisibility.value,
                imageUri = _selectedImageUri.value,
            ).onSuccess {
                _isSaving.value = false
                _saveSuccess.value = true
            }.onError { _, message ->
                _isSaving.value = false
                _error.value = message
            }
        }
    }

    fun clearError() { _error.value = null }

    companion object {
        const val PROFILE_VISIBILITY_PUBLIC = "public"
        const val PROFILE_VISIBILITY_FRIENDS = "friends_or_members"
        const val PROFILE_VISIBILITY_PRIVATE = "private"
        const val GENDER_MALE = "male"
        const val GENDER_FEMALE = "female"
    }
}
```

---

## 9. `EditProfileScreen.kt` — 레이아웃 구성

```kotlin
@Composable
fun EditProfileScreen(
    onBack: () -> Unit,
    onSaveSuccess: () -> Unit,
    profileViewModel: ProfileViewModel = hiltViewModel(),
    editProfileViewModel: EditProfileViewModel = hiltViewModel(),
) {
    val profile by profileViewModel.profile.collectAsState()
    val nickname by editProfileViewModel.nickname.collectAsState()
    val gender by editProfileViewModel.gender.collectAsState()
    val birthDate by editProfileViewModel.birthDate.collectAsState()
    val selfIntroduction by editProfileViewModel.selfIntroduction.collectAsState()
    val profileVisibility by editProfileViewModel.profileVisibility.collectAsState()
    val selectedImageUri by editProfileViewModel.selectedImageUri.collectAsState()
    val isSaving by editProfileViewModel.isSaving.collectAsState()
    val error by editProfileViewModel.error.collectAsState()
    val saveSuccess by editProfileViewModel.saveSuccess.collectAsState()

    // 기존 프로필 로드 → 편집 ViewModel 초기화
    LaunchedEffect(profile) {
        profile?.let { editProfileViewModel.loadProfile(it) }
    }

    // 저장 성공 → 이전 화면으로
    LaunchedEffect(saveSuccess) {
        if (saveSuccess) onSaveSuccess()
    }

    // 이미지 선택 + 크롭 헬퍼 (image-upload-guide.md 참고)
    val pickAndCrop = rememberPickAndCropImageLauncher(
        onCropped = { uri -> editProfileViewModel.setSelectedImageUri(uri) },
        onPermissionDenied = { /* snackbar 표시 */ },
    )

    MyPageSettingsFormTemplate(
        title = stringResource(R.string.edit_profile_title),
        onBack = onBack,
        isSaving = isSaving,
        onSave = { editProfileViewModel.save() },
    ) {
        // 인라인 에러
        if (error != null) {
            FormInlineErrorMolecule(message = error!!)
            Spacer(Modifier.height(AppSpacing.sm.dp))
        }

        // 프로필 사진
        MyPageEditProfilePhotoPickerOrganism(
            selectedImageUri = selectedImageUri,
            remoteImageUrl = profile?.profileImageUrl,
            onPickClick = { pickAndCrop() },
        )

        Spacer(Modifier.height(AppSpacing.md.dp))

        // 닉네임
        TextFieldAtom(
            value = nickname,
            onValueChange = { editProfileViewModel.setNickname(it) },
            placeholderResId = R.string.edit_profile_nickname_hint,
        )

        Spacer(Modifier.height(AppSpacing.sm.dp))

        // 생년월일 (YYYYMMDD)
        TextFieldAtom(
            value = birthDate,
            onValueChange = { editProfileViewModel.setBirthDate(it) },
            placeholderResId = R.string.edit_profile_birthdate_hint,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        Spacer(Modifier.height(AppSpacing.sm.dp))

        // 성별 선택
        MyPageEditProfileGenderSelectorOrganism(
            selectedGender = gender,
            onGenderSelected = { editProfileViewModel.setGender(it) },
        )

        Spacer(Modifier.height(AppSpacing.sm.dp))

        // 자기소개
        TextFieldAtom(
            value = selfIntroduction,
            onValueChange = { editProfileViewModel.setSelfIntroduction(it) },
            placeholderResId = R.string.edit_profile_intro_hint,
            maxLines = 5,
            singleLine = false,
        )

        Spacer(Modifier.height(AppSpacing.sm.dp))

        // 공개 범위
        MyPageEditProfileVisibilityOrganism(
            selectedVisibility = profileVisibility,
            onVisibilitySelected = { editProfileViewModel.setProfileVisibility(it) },
        )
    }
}
```

---

## 10. `MyPageEditProfileGenderSelectorOrganism` — 칩 선택 패턴

```kotlin
@Composable
fun MyPageEditProfileGenderSelectorOrganism(
    selectedGender: String?,
    onGenderSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        EditProfileViewModel.GENDER_MALE to stringResource(R.string.gender_male),
        EditProfileViewModel.GENDER_FEMALE to stringResource(R.string.gender_female),
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChipMedium(
                text = label,
                selected = selectedGender == value,
                onClick = { onGenderSelected(value) },
            )
        }
    }
}
```

공개 범위 선택(`MyPageEditProfileVisibilityOrganism`)도 동일 패턴:
`public / friends_or_members / private` 3개 FilterChip.

---

## 11. 신규 프로젝트 적용 체크리스트

```
□ 탭 정의: MyPageTabItems에 프로젝트 메뉴 아이템 정의
□ Route.kt에 마이페이지 관련 경로 상수 추가
□ ProfileViewModel: profileRepository.loadProfile() 연결
□ EditProfileViewModel: 프로필 필드 중 필요한 것만 유지 (불필요 필드 제거)
□ MyPageTabContent: 특수 액션(로그아웃/탈퇴) 처리 분기 추가
□ UserProfileDto: 프로젝트 필요 필드에 맞게 정의
□ strings.xml: 탭 라벨, 메뉴 아이템, 버튼 텍스트 l10n
□ 이미지 업로드: image-upload-guide.md 참고해서 연동
```

---

## 12. 자주 하는 실수

| 상황 | 잘못 | 올바른 방식 |
|------|------|------------|
| 탭 상태 저장 | `remember` | `rememberSaveable` — 화면 회전/프로세스 재시작 대응 |
| 로그아웃 처리 | ViewModel에서 직접 navigate | callback 패턴으로 올리고 Screen에서 navigate |
| 특수 액션 분기 | `when (item.route)` | `route == null` 확인 후 `labelResId`로 액션 구분 |
| 프로필 초기화 | `init { editProfileViewModel.loadProfile(...) }` | `LaunchedEffect(profile) { profile?.let { ... } }` (profile이 null에서 값으로 바뀔 때) |
| Scaffold 직접 | `5_pages`에서 `Scaffold { }` | `MyPageSettingsFormTemplate` 경유 |
| 계정 메뉴 하드코딩 | `MyPageTabItems.ACCOUNT_ITEMS` 고정 | `getAccountItems(emailVerified, phoneVerified, isAdmin)` 동적 생성 |

---

*원본 검증 프로젝트: `happy_v12` — `MyPageTabContent`, `EditProfileScreen`, `ProfileViewModel`, `EditProfileViewModel`, 13개 my-page organisms 기반*
