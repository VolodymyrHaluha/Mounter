# Виправлення локальних дублікатів NFC-моделі

У надісланому `src.zip` знайдено два файли:

- `app/src/main/java/com/example/mounter/CardSessions.kt`
- `app/src/main/java/com/example/mounter/attendance/CardSessions.kt`

Обидва оголошували `package com.example.mounter.attendance`, клас
`CardWorkSession` і функцію `hasActiveAttendance`. Шлях файла не змінює Kotlin-пакет.
Це спричиняє `Redeclaration`, `Conflicting overloads` і неоднозначність виклику
в `AttendanceGate.kt`. Імпорт із псевдонімом цього дублювання не усуває.

Замініть файл у `mounter/CardSessions.kt` версією з цієї гілки: вона містить
лише пакет і пояснення, без оголошень. Або видаліть цей зайвий файл.
Повна модель залишається в `mounter/attendance/CardSessions.kt`.
Змінювати пакет повної моделі не потрібно.

У тому самому ZIP залишилися старі тести. Оновіть з цієї гілки також:

- `app/src/test/java/com/example/mounter/CardSessionsTest.kt`: актуальні тести
  перевіряють серверний знімок; `saveCardEvent` і `confirmCardEvent` більше немає.
- `app/src/test/java/com/example/mounter/AttendanceAccessTest.kt`: активна тестова
  картка повинна мати employeeId, LOCAL, serverRevision і syncStatus=confirmed.

`app/build.gradle.kts` із ZIP не додає повторних sourceSets і не є причиною дубля.
Після оновлення виконайте Rebuild Project.
