# Подпись публичного APK 0.0.1

- Пакет: `ru.rikko.music`
- Сертификат: `CN=Valentin Paramut`
- SHA-256 сертификата: `d6c4a4678c1796f5bb30dbde79ebdf9d506c837faf8db12520b31a6428cc5b7d`
- SHA-256 файла `Vako-Music-v0.0.1.apk`: `58ed48520ff4e4703fc9ccc92ca5f56bdd02b266c55b039f8831d90fef331b81`

APK выровнен через `zipalign` и подписан через Android `apksigner`. `apksigner verify --min-sdk-version 29` завершился успешно. Ключ подписи и пароль не входят в репозиторий и GitHub Release.

При скачивании APK сравните SHA-256 с файлом `SHA256SUMS.txt` в релизе. Для обновления Android требует тот же сертификат; отладочные APK прежних прототипов подписаны другим ключом.
