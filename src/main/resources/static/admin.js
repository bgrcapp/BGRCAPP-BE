let attendanceStatusData = {
    date: '',
    totalCount: 0,
    checkedCount: 0,
    uncheckedCount: 0,
    people: []
};
let statusHideTimer;
let statusMessageVersion = 0;
let rosterUploadInProgress = false;
let attendanceStatusFilter = 'all';

// 페이지 로드 시 자동으로 현재 설정과 Excel 기반 출석 현황 불러오기
function initializeAdminData() {
    document.getElementById('attendanceDateInput').value = getLocalDateString();
    // 페이지를 처음 열 때의 조회 완료 문구가 업로드 결과를 덮어쓰지 않도록 알림은 표시하지 않는다.
    loadConfig({ showSuccess: false });
}

function getLocalDateString(date = new Date()) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
}

function formatAttendanceDate(date) {
    if (!date) return '기준일을 확인할 수 없습니다.';
    const [year, month, day] = date.split('-');
    return `${year}년 ${Number(month)}월 ${Number(day)}일`;
}

function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#039;');
}

function getAttendancePersonName(person) {
    const value = person?.name ?? person?.userName ?? person?.username ?? '';
    return String(value).trim();
}

function renderAttendanceStatus(searchText = '') {
    const roster = Array.isArray(attendanceStatusData.people)
        ? attendanceStatusData.people
            .map(person => ({ ...person, name: getAttendancePersonName(person) }))
            .filter(person => person.name)
        : [];
    const statusFilteredRoster = attendanceStatusFilter === 'checked'
        ? roster.filter(person => person.attended)
        : attendanceStatusFilter === 'unchecked'
            ? roster.filter(person => !person.attended)
            : roster;
    const filteredRoster = statusFilteredRoster.filter(person => person.name.includes(searchText.trim()));
    const totalCount = roster.length;
    const checkedCount = roster.filter(person => person.attended).length;
    const progress = totalCount === 0 ? 0 : Math.round((checkedCount / totalCount) * 100);

    document.getElementById('attendanceDate').textContent =
        `${formatAttendanceDate(attendanceStatusData.date)} · 출석 일지 기준`;
    document.getElementById('attendanceTotal').textContent = totalCount;
    document.getElementById('attendanceChecked').textContent = checkedCount;
    document.getElementById('attendanceUnchecked').textContent = totalCount - checkedCount;
    document.getElementById('attendanceProgressBar').style.width = `${progress}%`;
    document.getElementById('attendanceListCount').textContent =
        `${filteredRoster.length}/${statusFilteredRoster.length}명 표시`;

    document.querySelectorAll('[data-attendance-filter]').forEach(button => {
        const isActive = button.dataset.attendanceFilter === attendanceStatusFilter;
        button.classList.toggle('is-active', isActive);
        button.setAttribute('aria-pressed', String(isActive));
    });

    const listElement = document.getElementById('attendanceList');
    if (filteredRoster.length === 0) {
        listElement.innerHTML = '<div class="attendance-empty">검색 결과가 없습니다.</div>';
        return;
    }

    listElement.innerHTML = filteredRoster.map(person => {
        const statusClass = person.attended ? 'is-checked' : 'is-unchecked';
        const statusText = person.attended ? '출석' : '결석';
        const statusIcon = person.attended ? 'fa-circle-check' : 'fa-circle-minus';
        const toggleHint = person.attended ? '클릭하여 결석으로 변경' : '클릭하여 출석으로 변경';

        return `
            <div class="attendance-person-row">
                <div class="attendance-person-info">
                    <div class="attendance-person-name">${escapeHtml(person.name)}</div>
                </div>
                <button class="attendance-badge attendance-toggle ${statusClass}"
                        type="button"
                        data-attendance-toggle
                        data-serial-number="${escapeHtml(person.serialNumber)}"
                        data-person-name="${escapeHtml(person.name)}"
                        title="${toggleHint}">
                    <i class="fa-solid ${statusIcon}" aria-hidden="true"></i> ${statusText}
                </button>
            </div>
        `;
    }).join('');

    listElement.querySelectorAll('[data-attendance-toggle]').forEach(button => {
        button.addEventListener('click', () => {
            toggleAttendance(button.dataset.serialNumber, button.dataset.personName, button);
        });
    });
}

function setAttendanceStatusFilter(filter) {
    // 이미 선택한 출석·결석 카드를 다시 누르면 선택을 해제해 전체 명단으로 돌아간다.
    attendanceStatusFilter = filter === 'all' || attendanceStatusFilter === filter ? 'all' : filter;
    renderAttendanceStatus(document.getElementById('attendanceSearch').value);
}

function showAttendanceEmpty(message) {
    attendanceStatusData = {
        date: '',
        totalCount: 0,
        checkedCount: 0,
        uncheckedCount: 0,
        people: []
    };
    renderAttendanceStatus();
    document.getElementById('attendanceDate').textContent = message;
}

async function loadAttendanceStatus() {
    const dateInput = document.getElementById('attendanceDateInput');
    const selectedDate = dateInput.value || getLocalDateString();
    dateInput.value = selectedDate;

    try {
        const response = await fetch(`/api/admin/attendance?date=${encodeURIComponent(selectedDate)}`);
        const result = await response.json();
        if (!response.ok || !result.success) {
            throw new Error(result.message || `출석 현황을 불러올 수 없습니다 (HTTP ${response.status})`);
        }

        attendanceStatusData = result.data;
        renderAttendanceStatus(document.getElementById('attendanceSearch').value);
    } catch (error) {
        console.error('출석 현황 로드 실패:', error);
        showAttendanceEmpty(error.message);
    }
}

async function toggleAttendance(serialNumber, name, button) {
    const dateInput = document.getElementById('attendanceDateInput');
    const selectedDate = dateInput.value || getLocalDateString();
    if (!serialNumber) {
        showStatus('<i class="fa-solid fa-circle-xmark"></i> 오류 : 대상자 식별 정보를 찾을 수 없습니다.', 'error', false);
        return;
    }

    button.disabled = true;
    try {
        const query = new URLSearchParams({ date: selectedDate, serialNumber });
        const response = await fetch(`/api/admin/attendance/toggle?${query.toString()}`, {
            method: 'POST'
        });
        const result = await response.json().catch(() => null);
        if (!response.ok || !result?.success) {
            throw new Error(result?.message || `출석 상태 변경에 실패했습니다 (HTTP ${response.status})`);
        }

        attendanceStatusData = result.data;
        renderAttendanceStatus(document.getElementById('attendanceSearch').value);
        const changedPerson = result.data.people.find(person => String(person.serialNumber) === String(serialNumber));
        const changedStatus = changedPerson?.attended ? '출석' : '결석';
        showStatus(`<i class="fa-solid fa-circle-check"></i> 완료 : ${escapeHtml(name)}님을 ${changedStatus}으로 변경했습니다.`, 'success');
    } catch (error) {
        console.error('출석 상태 변경 실패:', error);
        showStatus('<i class="fa-solid fa-circle-xmark"></i> 오류 : ' + escapeHtml(error.message), 'error', false);
        button.disabled = false;
    }
}

function getDownloadFileName(contentDisposition) {
    const encodedName = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i);
    if (encodedName) return decodeURIComponent(encodedName[1]);

    const plainName = contentDisposition.match(/filename="?([^";]+)"?/i);
    return plainName ? plainName[1] : '출석일지.xlsx';
}

async function exportAttendanceLedger() {
    const dateInput = document.getElementById('attendanceDateInput');
    const selectedDate = dateInput.value || getLocalDateString();

    try {
        const response = await fetch(`/api/admin/attendance/export?date=${encodeURIComponent(selectedDate)}`);
        if (!response.ok) {
            const result = await response.json().catch(() => null);
            throw new Error(result?.message || `엑셀 파일을 내보낼 수 없습니다 (HTTP ${response.status})`);
        }

        const blob = await response.blob();
        const downloadUrl = URL.createObjectURL(blob);
        const downloadLink = document.createElement('a');
        downloadLink.href = downloadUrl;
        downloadLink.download = getDownloadFileName(response.headers.get('content-disposition') || '');
        document.body.appendChild(downloadLink);
        downloadLink.click();
        downloadLink.remove();
        URL.revokeObjectURL(downloadUrl);
        showStatus('<i class="fa-solid fa-circle-check"></i> 완료 : 출석 일지를 내려받았습니다', 'success');
    } catch (error) {
        console.error('출석 일지 내보내기 실패:', error);
        showStatus('<i class="fa-solid fa-circle-xmark"></i> 오류 : ' + error.message, 'error');
    }
}

// 현재 설정 불러오기
async function loadConfig({ showSuccess = true } = {}) {
    const refreshLoading = document.getElementById('refreshLoading');
    const statusVersionWhenStarted = statusMessageVersion;
    
    // 로딩 표시
    if (refreshLoading) {
        refreshLoading.style.display = 'inline-block';
    }
    
    try {
        const response = await fetch('/api/admin/config');
        if (!response.ok) {
            throw new Error(`설정을 불러올 수 없습니다 (HTTP ${response.status})`);
        }
        
        const data = await response.json();
        
        // 명단 인원수 표시
        document.getElementById('userCount').textContent = data.userCount || 0;
        
        // 파일 상태 표시
        const statusIcon = data.fileExists 
            ? '<i class="fa-solid fa-circle-check" style="color: #10b981;"></i>' 
            : '<i class="fa-solid fa-circle-xmark" style="color: #ef4444;"></i>';
        document.getElementById('fileStatus').innerHTML = statusIcon;

        await loadAttendanceStatus();
        
        if (showSuccess && !rosterUploadInProgress && statusMessageVersion === statusVersionWhenStarted) {
            showStatus('<i class="fa-solid fa-circle-check"></i> 완료 : 설정을 불러왔습니다', 'success');
        }
        
    } catch (error) {
        // 네트워크 에러나 기타 오류 - 서버 상태 카드는 표시하지 않음 (이미 위에서 처리됨)
        console.error('설정 로드 실패:', error);
        showAttendanceEmpty('출석 현황을 불러오지 못했습니다.');
        if (statusMessageVersion === statusVersionWhenStarted) {
            showStatus('<i class="fa-solid fa-circle-xmark"></i> 오류 : ' + error.message, 'error');
        }
    } finally {
        // 로딩 숨김
        if (refreshLoading) {
            refreshLoading.style.display = 'none';
        }
    }
}

// 상태 메시지 표시
function showStatus(message, type, autoHide = true) {
    const statusElement = document.getElementById('statusMessage');
    const messageVersion = ++statusMessageVersion;
    clearTimeout(statusHideTimer);
    statusElement.innerHTML = message;
    statusElement.className = 'status ' + type;
    // 이전 성공 알림이 숨김을 위해 설정한 inline display:none을 반드시 해제한다.
    // 그렇지 않으면 이후의 완료·실패·진행 문구도 CSS보다 inline 스타일이 우선해 보이지 않는다.
    statusElement.style.removeProperty('display');
    
    // 성공 메시지는 3초 후 자동으로 숨김
    if (type === 'success' && autoHide) {
        statusHideTimer = setTimeout(() => {
            if (statusMessageVersion === messageVersion) {
                statusElement.style.display = 'none';
            }
        }, 3000);
    }
}

// 드래그 앤 드롭 및 파일 선택 UI 초기화
function initializeAdminPage() {
    const uploadArea = document.getElementById('uploadArea');
    const fileInput = document.getElementById('excelFile');
    const selectedFileName = document.getElementById('selectedFileName');
    const attendanceSearch = document.getElementById('attendanceSearch');
    const attendanceDateInput = document.getElementById('attendanceDateInput');
    const attendanceDateLoad = document.getElementById('attendanceDateLoad');
    const attendanceExport = document.getElementById('attendanceExport');
    const rosterUploadButton = document.getElementById('rosterUploadButton');

    document.querySelectorAll('[data-attendance-filter]').forEach(button => {
        button.addEventListener('click', () => setAttendanceStatusFilter(button.dataset.attendanceFilter));
    });

    attendanceSearch.addEventListener('input', function(event) {
        renderAttendanceStatus(event.target.value);
    });

    attendanceDateLoad.addEventListener('click', loadAttendanceStatus);
    attendanceDateInput.addEventListener('change', loadAttendanceStatus);
    attendanceExport.addEventListener('click', exportAttendanceLedger);
    rosterUploadButton.addEventListener('click', uploadFile);

    // 클릭으로 파일 선택
    uploadArea.addEventListener('click', function() {
        fileInput.click();
    });

    // 파일 선택 시 파일명 표시
    fileInput.addEventListener('change', function(e) {
        if (e.target.files.length > 0) {
            const fileName = e.target.files[0].name;
            selectedFileName.innerHTML = '<i class="fa-solid fa-file-excel"></i> ' + fileName;
            selectedFileName.classList.add('show');
        }
    });

    // 드래그 앤 드롭
    ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
        uploadArea.addEventListener(eventName, preventDefaults, false);
    });

    function preventDefaults(e) {
        e.preventDefault();
        e.stopPropagation();
    }

    ['dragenter', 'dragover'].forEach(eventName => {
        uploadArea.addEventListener(eventName, function() {
            uploadArea.classList.add('drag-over');
        });
    });

    ['dragleave', 'drop'].forEach(eventName => {
        uploadArea.addEventListener(eventName, function() {
            uploadArea.classList.remove('drag-over');
        });
    });

    uploadArea.addEventListener('drop', function(e) {
        const files = e.dataTransfer.files;
        if (files.length > 0) {
            fileInput.files = files;
            const fileName = files[0].name;
            selectedFileName.innerHTML = '<i class="fa-solid fa-file-excel"></i> ' + fileName;
            selectedFileName.classList.add('show');
        }
    });
}

// 브라우저 캐시·탭 복원 등으로 DOMContentLoaded 이후에 스크립트가 실행되어도
// 업로드 버튼 이벤트가 빠지지 않도록 현재 문서 상태에 맞춰 즉시 초기화한다.
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initializeAdminPage, { once: true });
} else {
    initializeAdminPage();
}

if (document.readyState === 'complete') {
    initializeAdminData();
} else {
    window.addEventListener('load', initializeAdminData, { once: true });
}

// 파일 경로 저장하기
async function uploadFile() {
    const fileInput = document.getElementById('excelFile');
    const file = fileInput.files[0];
    
    if (!file) {
        showStatus('<i class="fa-solid fa-circle-xmark"></i> <strong>업로드 실패!</strong> 파일을 선택해주세요.', 'error', false);
        return;
    }

    // 파일 확장자 체크
    const fileName = file.name.toLowerCase();
    if (!fileName.endsWith('.xlsx') && !fileName.endsWith('.xls')) {
        showStatus('<i class="fa-solid fa-circle-xmark"></i> <strong>업로드 실패!</strong> 엑셀 파일만 선택 가능합니다 (.xlsx, .xls).', 'error', false);
        return;
    }

    // 로딩 표시
    const loadingSpinner = document.getElementById('uploadLoading');
    const uploadButton = document.getElementById('rosterUploadButton');
    loadingSpinner.style.display = 'inline-block';
    uploadButton.disabled = true;
    uploadButton.setAttribute('aria-busy', 'true');
    rosterUploadInProgress = true;
    showStatus('<i class="fa-solid fa-spinner fa-spin"></i> <strong>업로드 중...</strong> 명단을 등록하고 있습니다.', 'info', false);

    try {
        // FormData로 파일 전송
        const formData = new FormData();
        formData.append('file', file);
        
        const response = await fetch('/api/admin/roster', {
            method: 'POST',
            body: formData
        });

        const result = await response.json().catch(() => null);

        if (response.ok && result?.success) {
            // 파일 입력 및 UI 초기화
            fileInput.value = '';
            document.getElementById('selectedFileName').classList.remove('show');
            
            // 새 명단 기준으로 현황을 갱신하되, 업로드 완료 안내는 유지한다.
            await loadConfig({ showSuccess: false });
            showStatus('<i class="fa-solid fa-circle-check"></i> <strong>업로드 완료!</strong> 출석 명단 ' + (result.data.userCount || 0) + '명을 등록했습니다.', 'success', false);
        } else {
            const message = result?.message || `서버 응답 오류 (HTTP ${response.status})`;
            showStatus('<i class="fa-solid fa-circle-xmark"></i> <strong>업로드 실패!</strong> ' + message, 'error', false);
        }

    } catch (error) {
        console.error('명단 업로드 실패:', error);
        showStatus('<i class="fa-solid fa-circle-xmark"></i> <strong>업로드 실패!</strong> ' + error.message, 'error', false);
    } finally {
        // 로딩 숨김
        loadingSpinner.style.display = 'none';
        uploadButton.disabled = false;
        uploadButton.removeAttribute('aria-busy');
        rosterUploadInProgress = false;
    }
}
