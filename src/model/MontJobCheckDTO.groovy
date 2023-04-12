package model

import groovy.transform.CompileStatic
import groovy.transform.ToString

@CompileStatic
@ToString(includeNames = true, includeSuper = false)
class MontJobCheckDTO extends BaseRecord<MontJobCheckDTO> {
    Integer id

    String jobKey

    Date updatedDate

    static void doJobOnce(String key, Closure closure) {
        def one = new MontJobCheckDTO(jobKey: key).queryFields('id').one()
        if (one) {
            return
        }
        closure.call()
        new MontJobCheckDTO(jobKey: key, updatedDate: new Date()).add()
    }
}