package common

import groovy.transform.CompileStatic
import groovy.transform.ToString
import groovy.transform.builder.Builder
import groovy.util.logging.Slf4j
import model.MontEventDTO

@CompileStatic
@Builder
@Slf4j
@ToString(includeNames = true)
class Event {
    @CompileStatic
    static enum Type {
        vpc, ec2, lb, volume
    }

    Integer id

    Type type

    String reason

    Object result

    String message

    Date createdDate

    Event log(String message = '') {
        this.message = message
        log.info("{}/{}/{} - {}", type, reason, result, message)
        this
    }

    Integer add() {
        new MontEventDTO(id: id, type: type.name(), reason: reason, result: result?.toString(),
                message: message, createdDate: new Date()).add()
    }
}