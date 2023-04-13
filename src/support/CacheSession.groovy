package support

import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.ec2.model.Subnet
import com.amazonaws.services.ec2.model.Vpc
import groovy.transform.CompileStatic

@CompileStatic
@Singleton
class CacheSession {
    List<Vpc> vpcList = []

    List<Subnet> subnetList = []

    List<Instance> instanceList = []
}
