package radium.backend.annotations

/**
 * Annotation for providing color name suggestions for tab completion
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class ColorList
