let attendanceStatusData = {
    date: '',
    totalCount: 0,
    checkedCount: 0,
    uncheckedCount: 0,
    people: []
};

// 페이지 로드 시 자동으로 현재 설정과 Excel 기반 출석 현황 불러오기
window.onload = function() {
    loadConfig();
};

function formatAttendanceDate(date) {
    if (!date) return '기준일을 확인할 수 없습니다.';
    const [year, month, day] = date.split('-');
    return `${year}년 ${Number(month)}월 ${Number(day)}일`;
}

function escapeHtml(value) {
    return String(value)
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#039;');
}

function renderAttendanceStatus(searchText = '') {
    const roster = attendanceStatusData.people || [];
    const filteredRoster = roster.filter(person => person.name.includes(searchText.trim()));
    const totalCount = attendanceStatusData.totalCount || 0;
    const checkedCount = attendanceStatusData.checkedCount || 0;
    const progress = totalCount === 0 ? 0 : Math.round((checkedCount / totalCount) * 100);

    document.getElementById('attendanceDate').textContent =
        `${formatAttendanceDate(attendanceStatusData.date)} · 출석 일지 기준`;
    document.getElementById('attendanceTotal').textContent = totalCount;
    document.getElementById('attendanceChecked').textContent = checkedCount;
    document.getElementById('attendanceUnchecked').textContent = attendanceStatusData.uncheckedCount || 0;
    document.getElementById('attendanceProgressBar').style.width = `${progress}%`;
    document.getElementById('attendanceListCount').textContent =
        `${filteredRoster.length}/${totalCount}명 표시`;

    const listElement = document.getElementById('attendanceList');
    if (filteredRoster.length === 0) {
        listElement.innerHTML = '<div class="attendance-empty">검색 결과가 없습니다.</div>';
        return;
    }

    listElement.innerHTML = filteredRoster.map(person => {
        const statusClass = person.attended ? 'is-checked' : 'is-unchecked';
        const statusText = person.attended ? '출석 완료' : '미출석';
        const statusIcon = person.attended ? 'fa-circle-check' : 'fa-circle-minus';
        const meta = `${person.sheetName} · 연번 ${person.serialNumber}`;

        return `
            <div class="attendance-person-row">
                <div class="attendance-person-index">${escapeHtml(person.serial)}</div>
                <div class="attendance-person-info">
                    <div class="attendance-person-name">${escapeHtml(person.name)}</div>
                    <div class="attendance-person-meta">${escapeHtml(meta)}</div>
                </div>
                <span class="attendance-badge ${statusClass}">
                    <i class="fa-solid ${statusIcon}" aria-hidden="true"></i> ${statusText}
                </span>
            </div>
        `;
    }).join('');
}

function showAttendanceEmpty(message, source = '출석 일지 Excel') {
    attendanceStatusData = {
        date: '',
        totalCount: 0,
        checkedCount: 0,
        uncheckedCount: 0,
        people: []
    };
    document.getElementById('attendanceDataSource').textContent = source;
    renderAttendanceStatus();
    document.getElementById('attendanceDate').textContent = message;
}

async function loadAttendanceStatus() {
    try {
        const response = await fetch('/api/admin/attendance/today');
        const result = await response.json();
        if (!response.ok || !result.success) {
            throw new Error(result.message || `출석 현황을 불러올 수 없습니다 (HTTP ${response.status})`);
        }

        attendanceStatusData = result.data;
        document.getElementById('attendanceDataSource').textContent = '출석 일지 Excel';
        renderAttendanceStatus(document.getElementById('attendanceSearch').value);
    } catch (error) {
        console.error('출석 현황 로드 실패:', error);
        showAttendanceEmpty(error.message, 'Excel 조회 실패');
    }
}

// 현재 설정 불러오기
async function loadConfig() {
    const serverStatusCard = document.getElementById('serverStatusCard');
    const refreshLoading = document.getElementById('refreshLoading');
    
    // 로딩 표시
    if (refreshLoading) {
        refreshLoading.style.display = 'inline-block';
    }
    
    try {
        // 서버 상태 체크
        const statusResponse = await fetch('/api/status');
        
        console.log('응답 상태 코드:', statusResponse.status);
        
        if (statusResponse.status === 404) {
            // 서버가 꺼져있을 때
            serverStatusCard.style.display = 'block';
            serverStatusCard.classList.remove('online');
            document.getElementById('serverStatusIcon').className = 'fa-solid fa-circle-exclamation server-status-icon';
            document.getElementById('serverStatusTitle').textContent = '서버가 현재 꺼져있습니다';
            document.getElementById('serverStatusMessage').textContent = '서버를 실행한 후 새로고침 해주세요.';
            document.getElementById('currentPath').textContent = '-';
            document.getElementById('userCount').textContent = '-';
            document.getElementById('fileStatus').textContent = '-';
            showAttendanceEmpty('서버가 실행되지 않았습니다.', '서버 연결 대기');
            return;
        }
        
        if (!statusResponse.ok) {
            throw new Error(`서버 오류 (HTTP ${statusResponse.status})`);
        }

        // 서버가 정상일 때 - 초록색 카드 표시
        serverStatusCard.style.display = 'block';
        serverStatusCard.classList.add('online');
        document.getElementById('serverStatusIcon').className = 'fa-solid fa-circle-check server-status-icon';
        document.getElementById('serverStatusTitle').textContent = '서버가 정상 동작 중입니다';
        document.getElementById('serverStatusMessage').textContent = '모든 기능을 사용할 수 있습니다.';
        
        const response = await fetch('/api/admin/config');
        if (!response.ok) {
            throw new Error(`설정을 불러올 수 없습니다 (HTTP ${response.status})`);
        }
        
        const data = await response.json();
        
        // 현재 경로 표시
        document.getElementById('currentPath').textContent = data.excelPath || '설정된 경로가 없습니다';
        
        // 명단 인원수 표시
        document.getElementById('userCount').textContent = data.userCount || 0;
        
        // 파일 상태 표시
        const statusIcon = data.fileExists 
            ? '<i class="fa-solid fa-circle-check" style="color: #10b981;"></i>' 
            : '<i class="fa-solid fa-circle-xmark" style="color: #ef4444;"></i>';
        document.getElementById('fileStatus').innerHTML = statusIcon;

        await loadAttendanceStatus();
        
        showStatus('<i class="fa-solid fa-circle-check"></i> 완료 : 설정을 불러왔습니다', 'success');
        
    } catch (error) {
        // 네트워크 에러나 기타 오류 - 서버 상태 카드는 표시하지 않음 (이미 위에서 처리됨)
        console.error('설정 로드 실패:', error);
        showAttendanceEmpty('출석 현황을 불러오지 못했습니다.', '조회 실패');
        showStatus('<i class="fa-solid fa-circle-xmark"></i> 오류 : ' + error.message, 'error');
    } finally {
        // 로딩 숨김
        if (refreshLoading) {
            refreshLoading.style.display = 'none';
        }
    }
}

// 상태 메시지 표시
function showStatus(message, type) {
    const statusElement = document.getElementById('statusMessage');
    statusElement.innerHTML = message;
    statusElement.className = 'status ' + type;
    
    // 성공 메시지는 3초 후 자동으로 숨김
    if (type === 'success') {
        setTimeout(() => {
            statusElement.style.display = 'none';
        }, 3000);
    }
}

// 드래그 앤 드롭 및 파일 선택 UI 초기화
document.addEventListener('DOMContentLoaded', function() {
    const uploadArea = document.getElementById('uploadArea');
    const fileInput = document.getElementById('excelFile');
    const selectedFileName = document.getElementById('selectedFileName');
    const attendanceSearch = document.getElementById('attendanceSearch');

    attendanceSearch.addEventListener('input', function(event) {
        renderAttendanceStatus(event.target.value);
    });

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
});

// 파일 경로 저장하기
async function uploadFile() {
    const fileInput = document.getElementById('excelFile');
    const file = fileInput.files[0];
    
    if (!file) {
        showStatus('<i class="fa-solid fa-circle-xmark"></i> 오류 : 파일을 선택해주세요', 'error');
        return;
    }

    // 파일 확장자 체크
    const fileName = file.name.toLowerCase();
    if (!fileName.endsWith('.xlsx') && !fileName.endsWith('.xls')) {
        showStatus('<i class="fa-solid fa-circle-xmark"></i> 오류 : 엑셀 파일만 선택 가능합니다 (.xlsx, .xls)', 'error');
        return;
    }

    // 로딩 표시
    const loadingSpinner = document.getElementById('uploadLoading');
    loadingSpinner.style.display = 'inline-block';

    try {
        // FormData로 파일 전송
        const formData = new FormData();
        formData.append('file', file);
        
        const response = await fetch('/api/admin/upload', {
            method: 'POST',
            body: formData
        });

        const result = await response.json();

        if (response.ok) {
            showStatus('<i class="fa-solid fa-circle-check"></i> 완료 : 명단 ' + (result.data.userCount || 0) + '명 로드됨', 'success');
            
            // 파일 입력 및 UI 초기화
            fileInput.value = '';
            document.getElementById('selectedFileName').classList.remove('show');
            
            // 설정 다시 불러오기
            setTimeout(() => {
                loadConfig();
            }, 500);
        } else {
            showStatus('<i class="fa-solid fa-circle-xmark"></i> 오류 : 설정 실패 - ' + (result.message || '알 수 없는 오류'), 'error');
        }

    } catch (error) {
        console.error('설정 실패:', error);
        showStatus('<i class="fa-solid fa-circle-xmark"></i> 오류 : ' + error.message, 'error');
    } finally {
        // 로딩 숨김
        loadingSpinner.style.display = 'none';
    }
}
