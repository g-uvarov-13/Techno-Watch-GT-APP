package app.gtdial.lab;

/** View-only snapshot: byte counts advance after protocol responses, never after attempted TX. */
final class DialInstallProgress {
    final String phase,error,activePhase;
    final long completed,total,startedAtMs,updatedAtMs;
    final boolean mutationStarted;
    final boolean direct;
    DialInstallProgress(String phase,long completed,long total,long startedAtMs,String error,boolean mutationStarted,String activePhase){
        this(phase,completed,total,startedAtMs,error,mutationStarted,activePhase,false);
    }
    DialInstallProgress(String phase,long completed,long total,long startedAtMs,String error,boolean mutationStarted,String activePhase,boolean direct){
        this.direct=direct;
        this.phase=phase;this.completed=Math.max(0,completed);this.total=Math.max(0,total);
        this.startedAtMs=startedAtMs;updatedAtMs=android.os.SystemClock.elapsedRealtime();this.error=error==null?"":error;this.mutationStarted=mutationStarted;this.activePhase=activePhase;
    }
    boolean terminal(){return "COMPLETE".equals(phase)||"FAILED".equals(phase)||"CANCELLED".equals(phase);}
    int stage(){if(direct)return switch(activePhase){case "UPLOAD"->2;case "INSTALL"->3;case "APPLY"->4;case "COMPLETE"->5;default->1;};return switch(activePhase){case "PREPARE","CONFIRM"->0;case "UPLOAD","RECOVERY_READ"->2;case "VERIFY","RECOVERY_RECHECK"->3;case "INSTALL","APPLY","RECOVERY_COMMIT"->4;case "COMPLETE"->5;default->1;};}
    String title(boolean ru){if(direct&&"PREPARE".equals(phase))return "WAITING_FOR_OPERATION".equals(error)?(ru?"Ожидаю завершения текущей операции":"Waiting for the current operation"):(ru?"Подготавливаю соединение с часами":"Preparing watch connection");if(direct&&"COMPLETE".equals(phase))return ru?"Часы подтвердили установку и применение":"The watch confirmed installation and application";return switch(phase){
        case "PREPARE"->ru?"Проверка сборки на телефоне":"Checking package on phone";
        case "CONFIRM"->ru?"Ожидаю подтверждения":"Waiting for confirmation";
        case "CHECK"->ru?"Проверка состояния часов":"Checking watch state";
        case "BACKUP"->ru?"Подготовка резервной копии":"Preparing backup";
        case "BACKUP_REUSE"->ru?"Проверка сохранённой резервной копии":"Checking saved backup";
        case "RECOVERY_CHECK"->ru?"Проверка уже установленного циферблата":"Checking installed watch face";
        case "RECOVERY_READ"->ru?"Чтение уже установленного пакета":"Reading installed package";
        case "RECOVERY_RECHECK"->ru?"Сверка установленного циферблата":"Verifying installed watch face";
        case "RECOVERY_COMMIT"->ru?"Сохранение подтверждения установки":"Saving installation confirmation";
        case "RECHECK"->ru?"Повторная проверка часов":"Rechecking watch state";
        case "UPLOAD"->ru?"Передача нового циферблата":"Uploading new watch face";
        case "VERIFY"->ru?"Проверка записанного файла чтением":"Verifying uploaded file by readback";
        case "INSTALL"->ru?"Установка на часах":"Installing on watch";
        case "APPLY"->ru?"Применение и проверка результата":"Applying and checking result";
        case "COMPLETE"->"ALREADY_INSTALLED".equals(error)?(ru?"Этот циферблат уже установлен":"This watch face is already installed"):"RECOVERY_VERIFIED".equals(error)?(ru?"Установленный циферблат подтверждён":"Installed watch face verified"):(ru?"Циферблат установлен и проверен":"Watch face installed and verified");
        case "CANCELLED"->ru?"Установка не начата":"Installation did not start";
        default->ru?"Установка остановлена":"Installation stopped";
    };}
    String detail(boolean ru){
        if(!terminal())return ru?"Не отключайте часы. Свернуть окно можно — операция продолжится.":"Keep the watch connected. Minimizing this window keeps the operation running.";
        if(direct&&"COMPLETE".equals(phase))return ru?"Проверьте изображение, время и показатели на часах.":"Check the image, time and values on the watch.";
        if(direct){
            String code=error.split(" ",2)[0];String cause=switch(code){
                case "WAIT_TIMEOUT"->ru?"Текущая операция не завершилась за 35 секунд.":"The current operation did not finish within 35 seconds.";
                case "CONNECTION_NOT_READY"->ru?"Не удалось подготовить соединение с выбранными часами.":"The connection to the selected watch could not be prepared.";
                case "PACKAGE_GATE_FAILED"->ru?"Выбранный пакет недоступен или не прошёл проверку на телефоне.":"The selected package is unavailable or failed phone validation.";
                case "TIMEOUT"->ru?"Часы не ответили вовремя.":"The watch did not respond in time.";
                case "DISCONNECTED"->ru?"Соединение изменилось или прервалось.":"The connection changed or was lost.";
                case "TRANSPORT_FAILED"->ru?"Bluetooth не принял передачу команды.":"Bluetooth rejected command transmission.";
                case "MALFORMED_RESPONSE"->ru?"Ответ часов не соответствует ожидаемому результату команды.":"The watch response does not match the expected command result.";
                case "MEMORY_FAILED"->ru?"Недостаточно памяти для обработки пакета.":"There is not enough memory to process the package.";
                default->ru?"Установка завершилась ошибкой команды или ответа часов.":"Installation ended with a command or watch response error.";
            };
            return cause+(ru?" Установка остановлена без автоматического повтора.":" Installation stopped without automatic retry.")+"\n\n"+error
                +"\n"+(mutationStarted?(ru?"Передача начиналась; применение не подтверждено.":"Transmission started; application is unconfirmed."):(ru?"Передача файла не начиналась.":"File transmission did not start."));
        }
        if("COMPLETE".equals(phase)&&"ALREADY_INSTALLED".equals(error))return ru?"Пакет на часах проверен и совпадает с выбранным. Повторная передача и установка не понадобились.":"The package on the watch was verified and matches your selection. No upload or reinstallation was needed.";
        if("COMPLETE".equals(phase))return (ru?"Проверьте изображение, время и показатели на часах.":"Check the image, time and values on the watch.")+("LOG_FAILED".equals(error)?(ru?"\nЖурнал сохранён не полностью. Установка подтверждена; повторять её не нужно.":"\nThe log is incomplete. Installation was verified; do not repeat it."):"");
        if("CANCELLED".equals(phase))return ru?"Операция отменена до начала или подтверждение истекло. В этой попытке записи на часы не было. Результат прежней установки этим не проверен.":"The operation was cancelled before starting or confirmation expired. This attempt wrote nothing to the watch. The previous installation result was not checked.";
        String cause=switch(error){
            case "WAIT_TIMEOUT"->ru?"Текущая операция не завершилась за 35 секунд. Установка остановлена.":"The current operation did not finish within 35 seconds. Installation stopped.";
            case "SAME_IDENTITY_DIFFERENT_PACKAGE"->ru?"На часах находится другой пакет с тем же идентификатором. Содержимое не совпадает; передача не начата.":"The watch contains a different package with the same identity. The contents do not match; upload did not start.";
            case "CURRENT_NOT_OWNED"->ru?"Текущий циферблат не совпадает с подтверждённым прежним пакетом. Отправьте диагностику.":"The current face does not match the verified previous package. Export diagnostics.";
            case "CURRENT_CHANGED"->ru?"Текущий циферблат изменился во время резервного копирования. Установка остановлена.":"The current face changed during backup. Installation stopped.";
            case "BOLT01_MISSING"->ru?"Состав встроенных циферблатов не прошёл проверку. Отправьте диагностику.":"The built-in watch face inventory did not pass validation. Export diagnostics.";
            case "PRIOR_RECEIPT_REQUIRED"->ru?"Нет подтверждённой квитанции прежнего пакета. Отправьте диагностику.":"The previous package has no verified ownership receipt. Export diagnostics.";
            case "PENDING_RECOVERY_REQUIRED"->ru?"Результат прошлой установки не подтверждён. Откройте «Циферблаты» и нажмите «Проверить прошлую установку». До проверки установка других циферблатов заблокирована; повторная загрузка не нужна.":"The previous installation result is unverified. Open Watch faces and choose Check previous installation. Other installs are blocked until checked; no re-upload is needed.";
            case "RECOVERY_CURRENT_NOT_TARGET"->ru?"На часах выбран другой циферблат. Подтверждение уже установленного пакета остановлено. Отправьте диагностику.":"Another face is selected on the watch. Verification of the installed package stopped. Export diagnostics.";
            case "LOCAL_INVENTORY_UNKNOWN_USER","LOCAL_INVENTORY_UNKNOWN_GROUP","LOCAL_INVENTORY_COLLISION","LOCAL_INVENTORY_LIMIT","LOCAL_REQUIRED_IDENTITY_MISSING","LOCAL_TARGET_NOT_USER"->ru?"Часы вернули неожиданный список циферблатов. Точный ответ сохранён в диагностике; отправьте её для разбора.":"The watch returned an unexpected face inventory. The exact reply was saved in diagnostics; export it for review.";
            case "RECOVERY_PENDING_NOT_SOLE","RECOVERY_PENDING_INVALID","RECOVERY_PRIOR_CHANGED","RECOVERY_TARGET_INVALID","RECOVERY_TARGET_CHANGED","RECOVERY_PENDING_CHANGED","RECOVERY_CONTEXT_INVALID","RECOVERY_RECEIPT_FAILED","RECOVERY_READBACK_REQUIRED"->ru?"Сохранённое подтверждение незавершённой установки не прошло проверку. Повторная загрузка заблокирована; отправьте диагностику.":"The saved unfinished installation did not pass validation. Re-upload is blocked; export diagnostics.";
            case "BACKUP_WITNESS_FAILED","BACKUP_HASH_MISMATCH","BACKUP_TUPLE_INVALID","BACKUP_PATH","BACKUP_WITNESS_LIMIT","BACKUP_READBACK","BACKUP_EXISTING_CHANGED","BACKUP_DIRECTORY","BACKUP_SHORT_WRITE","BACKUP_TARGET_EXISTS"->ru?"Не удалось подтвердить сохранённую копию пакета. Операция остановлена; отправьте диагностику.":"The saved package copy could not be verified. The operation stopped; export diagnostics.";
            case "MEMORY_FAILED"->ru?"Недостаточно памяти для обработки пакета. Операция остановлена.":"There is not enough memory to process the package. The operation stopped.";
            case "CONNECTION_NOT_READY"->direct?(ru?"Не удалось подготовить соединение с выбранными часами. Установка остановлена.":"The connection to the selected watch could not be prepared. Installation stopped."):(ru?"Часы не готовы к установке. Отключите и подключите их в приложении; дождитесь готовности.":"The watch is not ready. Disconnect and reconnect it in this app, then wait until ready.");
            case "TIMEOUT"->ru?"Часы не ответили вовремя. Операция остановлена без повтора.":"The watch did not respond in time. The operation stopped without retry.";
            case "DISCONNECTED"->ru?"Подключение изменилось или прервалось.":"The connection changed or was lost.";
            case "LOG_FAILED"->ru?"Не удалось сохранить журнал. Следующие команды остановлены.":"The log could not be saved. Further commands stopped.";
            case "CONSENT_REJECTED","EXPIRED"->ru?"Подтверждение устарело. Установка не начата.":"Confirmation expired. Installation did not start.";
            default->ru?"Проверка или передача завершилась ошибкой. Отправьте диагностику; не повторяйте установку до разбора.":"Validation or transfer failed. Export diagnostics and stop further installation attempts until reviewed.";
        };
        return cause+"\n\n"+(mutationStarted?(ru?"Запись уже начиналась: результат не подтверждён. Не повторяйте установку; отправьте диагностику.":"Writing had started: the result is unconfirmed. Do not retry; export diagnostics."):(ru?"Передача нового файла не начиналась.":"The new file upload did not start."));
    }
}
