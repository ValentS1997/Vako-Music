package ru.rikko.music

/** Результат операции над набором медиафайлов: ошибки не скрывают уже выполненные шаги. */
object BatchOperations {
    data class Failure<T>(val item: T, val reason: String)
    data class Result<T>(val succeeded: List<T>, val failed: List<Failure<T>>)

    fun <T> run(items: List<T>, apply: (T) -> Boolean): Result<T> {
        val succeeded = mutableListOf<T>()
        val failed = mutableListOf<Failure<T>>()
        for (item in items) {
            try {
                if (apply(item)) succeeded += item
                else failed += Failure(item, "Android не изменил файл")
            } catch (error: Exception) {
                failed += Failure(item, error.message ?: "Ошибка доступа")
            }
        }
        return Result(succeeded, failed)
    }
}
