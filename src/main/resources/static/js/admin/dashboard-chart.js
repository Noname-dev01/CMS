// 대시보드 방문자 추이 차트 (Chart.js 2.x — SB Admin 2 vendor)
// 데이터는 index.html의 th:inline이 주입하는 DAILY_VISITORS ([{date: 'yyyy-MM-dd', count: N}, ...])
(function () {
  'use strict';

  var canvas = document.getElementById('visitorTrendChart');
  // 데이터가 배열이 아니거나 비었거나 캔버스가 없으면(폴백 문구 표시 상태) 조용히 종료 —
  // 템플릿 th:if와 이중 방어로 null 모델에서도 콘솔 오류를 내지 않는다
  if (typeof DAILY_VISITORS === 'undefined' || !Array.isArray(DAILY_VISITORS)
      || DAILY_VISITORS.length === 0 || !canvas) {
    return;
  }

  Chart.defaults.global.defaultFontFamily = 'Nunito', '-apple-system,system-ui,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif';
  Chart.defaults.global.defaultFontColor = '#858796';

  var labels = DAILY_VISITORS.map(function (d) { return d.date; });
  var counts = DAILY_VISITORS.map(function (d) { return d.count; });

  new Chart(canvas, {
    type: 'line',
    data: {
      labels: labels,
      datasets: [{
        label: '방문자 수',
        lineTension: 0.3,
        backgroundColor: 'rgba(78, 115, 223, 0.05)',
        borderColor: 'rgba(78, 115, 223, 1)',
        pointRadius: 3,
        pointBackgroundColor: 'rgba(78, 115, 223, 1)',
        pointBorderColor: 'rgba(78, 115, 223, 1)',
        pointHoverRadius: 3,
        pointHoverBackgroundColor: 'rgba(78, 115, 223, 1)',
        pointHoverBorderColor: 'rgba(78, 115, 223, 1)',
        pointHitRadius: 10,
        pointBorderWidth: 2,
        data: counts
      }]
    },
    options: {
      maintainAspectRatio: false,
      layout: {
        padding: { left: 10, right: 25, top: 25, bottom: 0 }
      },
      scales: {
        xAxes: [{
          gridLines: { display: false, drawBorder: false },
          ticks: { maxTicksLimit: 7 }
        }],
        yAxes: [{
          ticks: {
            min: 0,
            maxTicksLimit: 5,
            padding: 10,
            // 방문 수는 정수 — 소수 눈금 숨김
            callback: function (value) {
              return Number.isInteger(value) ? value : null;
            }
          },
          gridLines: {
            color: 'rgb(234, 236, 244)',
            zeroLineColor: 'rgb(234, 236, 244)',
            drawBorder: false,
            borderDash: [2],
            zeroLineBorderDash: [2]
          }
        }]
      },
      legend: { display: false },
      tooltips: {
        backgroundColor: 'rgb(255,255,255)',
        bodyFontColor: '#858796',
        titleMarginBottom: 10,
        titleFontColor: '#6e707e',
        titleFontSize: 14,
        borderColor: '#dddfeb',
        borderWidth: 1,
        xPadding: 15,
        yPadding: 15,
        displayColors: false,
        intersect: false,
        mode: 'index',
        caretPadding: 10,
        callbacks: {
          label: function (tooltipItem) {
            return '방문자 수: ' + tooltipItem.yLabel + '명';
          }
        }
      }
    }
  });
})();
