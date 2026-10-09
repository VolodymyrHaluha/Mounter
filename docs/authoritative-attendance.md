# Звіт: узгоджене підтвердження NFC-відміток

Зміни охоплюють Mounter і фактичний APP-TEST, а також доступний у APP-TEST код
LOCAL/GLOBAL. [Повний контракт і безпечне встановлення](https://github.com/VolodymyrHaluha/APP-TEST/blob/codex-authoritative-card-attendance/docs/authoritative-attendance.md).

## Основні файли

| Проєкт | Файли | Призначення |
|---|---|---|
| Mounter | AttendanceGate.kt, AttendanceContract.kt, attendance/CardSessions.kt | Версія provider, атомарний список, сукупний доступ до всіх розділів |
| Mounter | WorkHoursTimer.kt | Компактний перегляд усіх карток, окремі години, очікування LOCAL та історія |
| APP-TEST | AttendanceSession.kt, OfflineAttendance.kt, Attendance.kt | Постійний стан карток, окрема черга UUID, міграція старих даних без локального чергування |
| APP-TEST | AttendanceConfirmation.kt, AttendanceConfirmationJob.kt, GlobalAttendanceApi.kt, MainActivity.kt | Підтвердження LOCAL/GLOBAL, фонова звірка, збереження невідправлених NFC-подій |
| APP-TEST | MounterAttendanceBridge.kt, AndroidManifest.xml | Фактичний provider v2 й ContentObserver повідомлення |
| LOCAL | app.py, attendance_actions.py, server_mounter_state.py, migrations/004_mounter_confirmations.sql | Серверна хронологія, часові межі, ревізії та outbox LOCAL → GLOBAL |
| GLOBAL | app.py, server_mounter_state.py, migrations/004_mounter_confirmations.sql | Авторизований прийом результатів LOCAL та кеш актуального стану |

Захист від дубльованого зчитування працює окремо для NFC-тексту й враховує
нещодавно збережені події після перезапуску процесу. Старі UUID не створюються
повторно при надсиланні, поверненні між додатками чи очищенні черги фото.
Скасування фотографування не є подією й не закриває підтверджену сесію.

## Межі перевірки

Пройшли 80 JVM-тестів APP-TEST, 22 JVM-тести Mounter та 21 Python-тест
серверних контрактів/хронології. Debug APK обох додатків зібрано з тимчасовими
налаштуваннями, описаними нижче. Тести моделей, JSON-контракту й Flask-маршрутів виконуються локально;
PostgreSQL у контрактних тестах замінено тестовими з'єднаннями. Це перевіряє
правила та параметри транзакцій, але не є перевіркою реальної БД або планшета.

Android-збірки перевіряються з наявними JDK 21 і SDK 36.1 через тимчасові
налаштування, з пропуском AAR metadata check. APP-TEST додатково використовує
наявний AGP 9.3.3 замість оголошеного 9.3.1. Початкові JDK/SDK/AGP вимоги
репозиторіїв не змінено. Повна стандартна збірка потребує встановлених у
проєктах JDK 25/SDK 37(37.1) та відповідних версій AGP.

APP-TEST має старий ScreenBrightnessTest.kt у JVM-наборі, який імпортує Compose
instrumentation API та посилається на відсутній MaximumBrightnessForDialog.
Його тимчасово виключено з локальної JVM-перевірки й відновлено без змін.
Відповідна проблема повного старого набору тестів залишається окремою.

Не виконано встановлення APK, перевірку підписів і Cloudflare credentials,
підключення до робочих LOCAL/GLOBAL, SQL-міграції реальної БД, інструментальні
тести на Android та реальні переходи/перезапуски/відмітки на іншому планшеті.
Для цих сценаріїв у APP-TEST підготовлено контрактні тести й інструкцію розгортання.
