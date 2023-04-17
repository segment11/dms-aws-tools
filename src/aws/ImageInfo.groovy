package aws

import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor

@CompileStatic
@TupleConstructor
class ImageInfo {
    String id
    String name
    String architecture
}
