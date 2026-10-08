# Локальні відмітки й таймер «Робочі години»

Mounter читає успішно збережений локальний стан app-test через ContentProvider.
Для відкриття меню після приходу не потрібне підтвердження LOCAL.

Потрібно оновити **Mounter, APP-TEST, LOCAL і GLOBAL**. Зміни APP-TEST підготовлено
для `VolodymyrHaluha/APP-TEST`, commit `608dd88bd2698ef9d999db70c5dcd293675600db`.
Оновлені Android-код, сервери, тести й інструкція доступні в гілці
[`codex-offline-attendance-20261008`](https://github.com/VolodymyrHaluha/APP-TEST/tree/codex-offline-attendance-20261008).
Докладні кроки: [APP-TEST — offline-attendance.md](https://github.com/VolodymyrHaluha/APP-TEST/blob/codex-offline-attendance-20261008/docs/offline-attendance.md).

Спочатку оновіть сервери та додайте модулі `attendance_actions.py`, потім установіть
обидва додатки без очищення даних. Перша NFC-відмітка після оновлення починає нову
локальну зміну; встановлюйте оновлення перед початком роботи.

Таймер на головному екрані розташовано між кількістю об’єктів і «Дані на пристрої».
Повторна збережена відмітка тією самою карткою завершує зміну. Завершені години
показуються на екрані входу. Перезапуск не скидає час; синхронізація UUID не
перемикає стан повторно. Усі часові мітки зберігає app-test.

LOCAL зберігає `client_action` у JSON пристрою та не змінює його під час
перерахунку історії. GLOBAL передає цю дію при імпорті. Старі записи без поля
обробляються за попередньою хронологічною логікою; міграція БД не потрібна.

Скрипти `integrate_attendance.py`, `upgrade_attendance_return.py`,
`update_attendance_bridge.py` та шаблони в цій папці належать до **попередньої
серверної інтеграції**. Не запускайте їх поверх нової локальної інтеграції.
Її попередню документацію збережено в [README-server-confirmation.md](README-server-confirmation.md).