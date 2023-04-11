package model

import groovy.transform.CompileStatic
import groovy.transform.ToString

@CompileStatic
@ToString(includeNames = true, includeSuper = false)
class MontEventDTO extends BaseRecord<MontEventDTO> {
    Integer id

    String type

    String reason

    String result

    String message

    Date createdDate
}