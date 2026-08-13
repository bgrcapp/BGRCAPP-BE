let attendanceStatistics = { monthlyStatistics: [], people: [] };

function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#039;');
}

function formatNumber(value) {
    return Number(value || 0).toLocaleString('ko-KR');
}

function setStatisticsStatus(message, isError = false) {
    const element = document.getElementById('statisticsStatus');
    element.textContent = message;
    element.classList.toggle('is-error', isError);
}

function renderMonthlyStatistics(months) {
    const chart = document.getElementById('monthlyChart');
    const tableBody = document.getElementById('monthlyTableBody');
    if (!months.length) {
        chart.innerHTML = '<p class="statistics-empty">집계할 출석 일지가 없습니다.</p>';
        tableBody.innerHTML = '<tr><td colspan="6" class="statistics-empty">집계할 출석 일지가 없습니다.</td></tr>';
        return;
    }

    const maximum = Math.max(...months.map(month => month.mealCount), 1);
    chart.innerHTML = months.map(month => {
        const height = Math.max(8, Math.round((month.mealCount / maximum) * 100));
        return `
            <div class="monthly-bar-item" title="${escapeHtml(month.month)} ${formatNumber(month.mealCount)}건">
                <strong>${formatNumber(month.mealCount)}</strong>
                <div class="monthly-bar-track"><span style="height:${height}%"></span></div>
                <small>${escapeHtml(month.month.replace(/^\d{4}년 /, ''))}</small>
                <em>누계 ${formatNumber(month.cumulativeMealCount)}건</em>
            </div>`;
    }).join('');

    tableBody.innerHTML = months.map(month => `
        <tr>
            <th scope="row">${escapeHtml(month.month)}</th>
            <td><strong>${formatNumber(month.mealCount)}건</strong></td>
            <td class="cumulative-count">${formatNumber(month.cumulativeMealCount)}건</td>
            <td>${formatNumber(month.uniqueUserCount)}명</td>
            <td>${formatNumber(month.attendanceDayCount)}일</td>
            <td>${Number(month.averageDailyCount || 0).toFixed(1)}명</td>
        </tr>`).join('');
}

function renderPersonStatistics(searchText = '') {
    const normalizedSearch = searchText.trim();
    const people = (attendanceStatistics.people || [])
        .map((person, index) => ({ ...person, rank: index + 1 }))
        .filter(person => person.name.includes(normalizedSearch));
    const tableBody = document.getElementById('personTableBody');
    document.getElementById('personTableDescription').textContent =
        `${formatNumber(people.length)}/${formatNumber((attendanceStatistics.people || []).length)}명 표시 · 이용 횟수가 많은 순`;

    if (!people.length) {
        tableBody.innerHTML = '<tr><td colspan="4" class="statistics-empty">검색 결과가 없습니다.</td></tr>';
        return;
    }

    tableBody.innerHTML = people.map(person => `
        <tr>
            <td><span class="person-rank">${person.rank}</span></td>
            <th scope="row">${escapeHtml(person.name)}</th>
            <td><strong class="visit-count">${formatNumber(person.visitCount)}회</strong></td>
            <td>${escapeHtml(person.lastAttendanceDate || '-')}</td>
        </tr>`).join('');
}

function renderStatistics(data) {
    attendanceStatistics = data || { monthlyStatistics: [], people: [] };
    const monthlyStatistics = attendanceStatistics.monthlyStatistics || [];
    document.getElementById('totalMealCount').textContent = `${formatNumber(attendanceStatistics.totalMealCount)}건`;
    document.getElementById('uniqueUserCount').textContent = `${formatNumber(attendanceStatistics.uniqueUserCount)}명`;
    document.getElementById('latestMonthMealCount').textContent = `${formatNumber(attendanceStatistics.latestMonthMealCount)}건`;
    document.getElementById('sourceFileCount').textContent = `${formatNumber(attendanceStatistics.sourceFileCount)}개`;
    document.getElementById('latestMonthLabel').textContent = attendanceStatistics.latestMonth
        ? `${attendanceStatistics.latestMonth} 제공 건수`
        : '최근 월 제공 건수';
    renderMonthlyStatistics(monthlyStatistics);
    renderPersonStatistics(document.getElementById('personSearch').value);
    setStatisticsStatus(`출석 일지 ${formatNumber(attendanceStatistics.sourceFileCount)}개를 기준으로 집계했습니다.`);
}

async function loadStatistics() {
    const refreshButton = document.getElementById('statisticsRefresh');
    refreshButton.disabled = true;
    refreshButton.classList.add('is-loading');
    try {
        const response = await fetch('/api/admin/statistics', { cache: 'no-store' });
        const result = await response.json();
        if (!response.ok || !result.success) {
            throw new Error(result.message || `통계를 불러올 수 없습니다 (HTTP ${response.status})`);
        }
        renderStatistics(result.data);
    } catch (error) {
        console.error('출석 통계 로드 실패:', error);
        setStatisticsStatus(`통계를 불러오지 못했습니다: ${error.message}`, true);
    } finally {
        refreshButton.disabled = false;
        refreshButton.classList.remove('is-loading');
    }
}

document.getElementById('statisticsRefresh').addEventListener('click', loadStatistics);
document.getElementById('personSearch').addEventListener('input', event => renderPersonStatistics(event.target.value));
loadStatistics();
