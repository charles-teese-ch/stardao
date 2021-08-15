package io.stardog.stardao.kotlin.partial.annotations

@Target(AnnotationTarget.FIELD)
annotation class PartialField(val type: String = "Partial", val required: PartialFieldRequired = PartialFieldRequired.OPTIONAL, val className: String = "")

enum class PartialFieldRequired { REQUIRED, OPTIONAL, ABSENT }
