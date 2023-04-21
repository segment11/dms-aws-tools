package aws

import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor

@CompileStatic
@TupleConstructor
class InstanceTypeInfo2 {
    String instanceType
    Long memMB
    Integer cpuVCore
    String architecture
}
