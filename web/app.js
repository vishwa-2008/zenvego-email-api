document.addEventListener('DOMContentLoaded', () => {
  const emailStep = document.getElementById('emailStep');
  const otpStep = document.getElementById('otpStep');
  const authView = document.getElementById('authView');
  const dashboardView = document.getElementById('dashboardView');
  const emailInput = document.getElementById('emailInput');
  const sendOtpBtn = document.getElementById('sendOtpBtn');
  const verifyBtn = document.getElementById('verifyBtn');
  const resendBtn = document.getElementById('resendBtn');
  const changeEmailBtn = document.getElementById('changeEmailBtn');
  const displayEmail = document.getElementById('displayEmail');
  const messageBox = document.getElementById('messageBox');
  const otpInputs = document.querySelectorAll('.otp-input');
  const cooldownSection = document.getElementById('cooldownSection');
  const resendSection = document.getElementById('resendSection');
  const cooldownTimer = document.getElementById('cooldownTimer');
  const logoutBtn = document.getElementById('logoutBtn');
  const yearSpan = document.getElementById('yearSpan');

  if (yearSpan) yearSpan.textContent = new Date().getFullYear();

  let currentEmail = '';
  let cooldownInterval = null;

  // Initialize OTP Input Listeners
  otpInputs.forEach((input, index) => {
    input.addEventListener('input', (e) => {
      const val = e.target.value;
      if (!/^\d*$/.test(val)) {
        e.target.value = '';
        return;
      }
      if (val.length === 1 && index < otpInputs.length - 1) {
        otpInputs[index + 1].focus();
      }
      checkOtpFilled();
    });

    input.addEventListener('keydown', (e) => {
      if (e.key === 'Backspace' && !e.target.value && index > 0) {
        otpInputs[index - 1].focus();
      }
    });

    input.addEventListener('paste', (e) => {
      e.preventDefault();
      const pastedData = (e.clipboardData || window.clipboardData).getData('text').trim();
      if (/^\d{6}$/.test(pastedData)) {
        pastedData.split('').forEach((char, i) => {
          if (otpInputs[i]) otpInputs[i].value = char;
        });
        otpInputs[5].focus();
        checkOtpFilled();
      }
    });
  });

  function getCombinedOtp() {
    return Array.from(otpInputs).map(input => input.value).join('');
  }

  function checkOtpFilled() {
    const otp = getCombinedOtp();
    verifyBtn.disabled = otp.length !== 6;
  }

  function showMessage(msg, type = 'error') {
    messageBox.textContent = msg;
    messageBox.className = `message-box ${type}`;
    messageBox.classList.remove('hidden');
  }

  function hideMessage() {
    messageBox.classList.add('hidden');
  }

  function startCooldownTimer(durationSec = 60) {
    let timer = durationSec;
    resendSection.classList.add('hidden');
    cooldownSection.classList.remove('hidden');

    updateTimerDisplay(timer);

    clearInterval(cooldownInterval);
    cooldownInterval = setInterval(() => {
      timer--;
      updateTimerDisplay(timer);
      if (timer <= 0) {
        clearInterval(cooldownInterval);
        cooldownSection.classList.add('hidden');
        resendSection.classList.remove('hidden');
      }
    }, 1000);
  }

  function updateTimerDisplay(seconds) {
    const mins = String(Math.floor(seconds / 60)).padStart(2, '0');
    const secs = String(seconds % 60).padStart(2, '0');
    cooldownTimer.textContent = `${mins}:${secs}`;
  }

  // Send OTP handler
  async function handleSendOtp() {
    const email = emailInput.value.trim();
    if (!email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      showMessage('Please enter a valid email address.');
      return;
    }

    hideMessage();
    sendOtpBtn.disabled = true;
    sendOtpBtn.querySelector('.btn-text').textContent = 'Sending Code...';

    try {
      const response = await fetch('/send-otp', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: `email=${encodeURIComponent(email)}&username=${encodeURIComponent(email.split('@')[0])}`
      });

      const data = await response.json();

      if (response.ok && data.status === 'success') {
        currentEmail = email;
        displayEmail.textContent = email;
        emailStep.classList.add('hidden');
        otpStep.classList.remove('hidden');
        otpInputs[0].focus();
        startCooldownTimer(60);
        showMessage('Verification code sent! Check your inbox.', 'success');
      } else {
        showMessage(data.message || 'Failed to send verification code.');
      }
    } catch (err) {
      showMessage('Network error. Please make sure the server is running.');
    } finally {
      sendOtpBtn.disabled = false;
      sendOtpBtn.querySelector('.btn-text').textContent = 'Send Verification Code';
    }
  }

  // Verify OTP handler
  async function handleVerifyOtp() {
    const otp = getCombinedOtp();
    if (otp.length !== 6) {
      showMessage('Please enter all 6 digits.');
      return;
    }

    hideMessage();
    verifyBtn.disabled = true;
    verifyBtn.querySelector('.btn-text').textContent = 'Verifying...';

    try {
      const response = await fetch('/verify-otp', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: `email=${encodeURIComponent(currentEmail)}&otp=${encodeURIComponent(otp)}`
      });

      const data = await response.json();

      if (response.ok && data.status === 'success') {
        // Successful verification! Show Dashboard
        authView.classList.add('hidden');
        dashboardView.classList.remove('hidden');

        document.getElementById('dashboardEmail').textContent = currentEmail;
        document.getElementById('dashboardUserId').textContent = 'ZNG-' + Math.floor(100000 + Math.random() * 900000);
        document.getElementById('dashboardLastLogin').textContent = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      } else {
        showMessage(data.message || 'Invalid or expired OTP code.');
        otpInputs.forEach(i => i.value = '');
        otpInputs[0].focus();
      }
    } catch (err) {
      showMessage('Network error while verifying code.');
    } finally {
      verifyBtn.disabled = false;
      verifyBtn.querySelector('.btn-text').textContent = 'Verify & Sign In';
    }
  }

  // Event Listeners
  sendOtpBtn.addEventListener('click', handleSendOtp);

  emailInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') handleSendOtp();
  });

  verifyBtn.addEventListener('click', handleVerifyOtp);

  resendBtn.addEventListener('click', () => {
    otpInputs.forEach(i => i.value = '');
    handleSendOtp();
  });

  changeEmailBtn.addEventListener('click', () => {
    hideMessage();
    otpInputs.forEach(i => i.value = '');
    otpStep.classList.add('hidden');
    emailStep.classList.remove('hidden');
    emailInput.focus();
  });

  logoutBtn.addEventListener('click', () => {
    dashboardView.classList.add('hidden');
    authView.classList.remove('hidden');
    otpStep.classList.add('hidden');
    emailStep.classList.remove('hidden');
    emailInput.value = '';
    hideMessage();
  });
});
