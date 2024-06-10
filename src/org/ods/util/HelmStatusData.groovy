package org.ods.util

import com.cloudbees.groovy.cps.NonCPS
import groovy.transform.Immutable
import groovy.transform.PackageScope
import groovy.transform.TypeChecked

@TypeChecked
@Immutable
class HelmStatusSimpleData {

    String releaseName
    String releaseRevision
    String namespace
    String deployStatus
    String deployDescription
    String lastDeployed
    List<HelmStatusResource> resources

    static HelmStatusSimpleData fromJsonObject(Object jsonObject) {
        from(HelmStatusData.fromJsonObject(jsonObject))
    }

    @SuppressWarnings(['NestedForLoop'])
    static HelmStatusSimpleData from(HelmStatusData status) {
        def simpleResources = []
        for (resourceList in status.info.resources.values()) {
            for (hsr in resourceList) {
                simpleResources << new HelmStatusResource(kind: hsr.kind, name: hsr.metadataName)
            }
        }
        def map = [
            releaseName: status.name,
            releaseRevision: status.version,
            namespace: status.namespace,
            deployStatus: status.info.status,
            deployDescription: status.info.description,
            lastDeployed: status.info.lastDeployed,
            resources: simpleResources,
        ]
        new HelmStatusSimpleData(map)
    }

    Map<String, List<String>> getResourcesByKind(List<String> kinds) {
        def deploymentResources = resources.findAll { it.kind in kinds }
        Map<String, List<String>> resourcesByKind = [:]
        deploymentResources.each {
            if (!resourcesByKind.containsKey(it.kind)) {
                resourcesByKind[it.kind] = []
            }
            resourcesByKind[it.kind] << it.name
        }
        resourcesByKind
    }

    @NonCPS
    Map<String, Object> toMap() {
        def result = [
            releaseName: releaseName,
            releaseRevision: releaseRevision,
            namespace: namespace,
            deployStatus: deployStatus,
            deployDescription: deployDescription,
            lastDeployed: lastDeployed,
            resources: resources.collect { [kind: it.kind, name: it.name] }
        ]
        result
    }

    @NonCPS
    String toString() {
        toMap().toMapString()
    }

}

@TypeChecked
@Immutable
class HelmStatusResource {

    String kind
    String name

}

// Relevant data returned by helm status --show-resources -o json
@TypeChecked
class HelmStatusData {

    // release name
    String name
    String namespace

    HelmStatusInfoData info

    // version is an integer but we map it to a string.
    String version

    @SuppressWarnings(['IfStatementBraces'])
    static HelmStatusData fromJsonObject(Object object) {
        try {
            def jsonObject = ensureMap(object, "")

            Map<String,Object> status = [:]

            // Constructors of classes receiving the data catches missing keys or unexpected types and
            // report them in summary as IllegalArgumentException.
            if (jsonObject.name) status.name = jsonObject.name
            if (jsonObject.version) status.version = jsonObject.version
            if (jsonObject.namespace) status.namespace = jsonObject.namespace

            def infoObject = ensureMap(jsonObject.info, "info")
            Map<String, Object> info = [:]

            if (infoObject.status) info.status = infoObject.status
            if (infoObject.description) info.description = infoObject.description
            if (infoObject['last_deployed']) info.lastDeployed = infoObject['last_deployed']

            def resourcesObject = ensureMap(infoObject.resources, "info.resources")
            // All resources are in an json object which organize resources by keys
            // Examples are  "v1/Cluster", "v1/ConfigMap"  "v1/Deployment", "v1/Pod(related)"
            // For these keys the map contains list of resources.
            Map<String, List<HelmStatusResourceData>> resourcesMap = [:]
            for (entry in resourcesObject.entrySet()) {
                def key = entry.key as String
                def resourceList = ensureList(entry.value, "info.resources.${key}")
                def resources = []
                resourceList.eachWithIndex { resourceJsonObject, i ->
                    def resourceData = fromJsonObjectHelmStatusResource(
                        resourceJsonObject,
                        "info.resources.${key}.[${i}]")
                    if (resourceData != null) {
                        resources << resourceData
                    }
                }
                resourcesMap << [(key): resources]
            }
            info.resources = resourcesMap
            status.info = new HelmStatusInfoData(info)
            new HelmStatusData(status)
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Unexpected helm status information in JSON at 'info': ${e.getMessage()}")
        }
    }

    @SuppressWarnings(['Instanceof'])
    private static Map ensureMap(Object obj, String context) {
        if (obj == null) {
            return [:]
        }
        if (!(obj instanceof Map)) {
            def msg = context ?
                "${context}: expected JSON object, found ${obj.getClass()}":
                "Expected JSON object, found ${obj.getClass()}"

            throw new IllegalArgumentException(msg)
        }
        obj as Map
    }

    @SuppressWarnings(['Instanceof'])
    private static List ensureList(Object obj, String context) {
        if (obj == null) {
            return []
        }
        if (!(obj instanceof List)) {
            throw new IllegalArgumentException(
                "${context}: expected JSON array, found ${obj.getClass()}")
        }
        obj as List
    }

    @SuppressWarnings(['IfStatementBraces', 'Instanceof'])
    private static HelmStatusResourceData fromJsonObjectHelmStatusResource(
        resourceJsonObject, String context) {
        def resourceObject = ensureMap(resourceJsonObject, context)
        Map<String, Object> resource = [:]
        if (resourceObject.apiVersion) resource.apiVersion = resourceObject.apiVersion
        if (resourceObject.kind) resource.kind = resourceObject.kind
        Map<String, Object> metadataObject = ensureMap(
            resourceObject.metadata, "${context}.metadata")
        if (metadataObject.name) resource.metadataName = metadataObject.name
        if (resource.kind == "PodList") {
            return null
        }
        if (resource.kind == "Pod") {
            def statusObject = ensureMap(resourceObject.status,
                "${context}.status")
            List<HelmStatusContainerStatusData> containerStatuses = []
            def containerStatusesJsonArray = ensureList(statusObject.containerStatuses,
                "${context}.status.containerStatuses")
            containerStatusesJsonArray.eachWithIndex { cs, int csi ->
                def cso = ensureMap(cs, "${context}.status.containerStatuses[${csi}]")
                Map<String, Object> containerStatus = [:]
                if (cso.name) containerStatus.name = cso.name
                if (cso.image) containerStatus.image = cso.image
                if (cso.imageID) containerStatus.imageID = cso.imageID
                containerStatuses << new HelmStatusContainerStatusData(cso)
            }
            resource.containerStatuses = containerStatuses
        }
        try {
            new HelmStatusResourceData(resource)
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Unexpected helm status JSON at '${context}': ${e.getMessage()}")
        }
    }

    @SuppressWarnings(['PublicMethodsBeforeNonPublicMethods'])
    static @PackageScope void handleMissingKeysOrBadTypes(List<String> missingKeys, List<String> badTypes) {
        if (missingKeys || badTypes) {
            def msgs = []
            if (missingKeys) {
                msgs << "Missing keys: ${missingKeys.join(', ')}"
            }
            if (badTypes) {
                msgs << "Bad types: ${badTypes.join(', ')}"
            }
            throw new IllegalArgumentException(msgs.join("."))
        }
    }

    @SuppressWarnings(['Instanceof'])
    HelmStatusData(Map<String, Object> map) {
        def missingKeys = []
        def badTypes = []

        def stringTypes = [ "name", "namespace"]
        for (att in stringTypes) {
            if (!map.containsKey(att)) {
                missingKeys << att
            } else if (!(map[att] instanceof String)) {
                badTypes << "${att}: expected String, found ${map[att].getClass()}"
            }
        }
        if (!map.containsKey('info')) {
            missingKeys << 'info'
        } else if (!(map['info'] instanceof HelmStatusInfoData)) {
            badTypes << "info: expected HelmStatusInfoData, found ${map['info'].getClass()}"
        }

        if (!map.containsKey('version')) {
            missingKeys << 'version'
        }
        handleMissingKeysOrBadTypes(missingKeys, badTypes)

        this.name = map['name'] as String
        this.namespace = map['namespace'] as String
        this.info = map['info'] as HelmStatusInfoData
        this.version = map['version'].toString()
    }

}

//Relevant helm status info data we want to capture
@TypeChecked
class HelmStatusInfoData {

    // deployment status - see `helm status --help` for full list
    // Example: "deployed"
    String status
    // description of the release (can be completion message or error message)
    // Example:  "Upgrade complete"
    String description
    // last-deployed field.
    // Example: "2024-03-04T15:21:09.34520527Z"
    String lastDeployed

    Map<String, List<HelmStatusResourceData>> resources

    @SuppressWarnings(['Instanceof'])
    HelmStatusInfoData(Map<String, Object> map) {
        def missingKeys = []
        def badTypes = []

        def stringTypes = [ "status", "description", "lastDeployed"]
        for (att in stringTypes) {
            if (!map.containsKey(att)) {
                missingKeys << att
            } else if (!(map[att] instanceof String)) {
                badTypes << "${att}: expected String, found ${map[att].getClass()}"
            }
        }
        if (!map.containsKey('resources')) {
            missingKeys << 'resources'
        } else if (!(map['resources'] instanceof Map)) {
            badTypes << "resources: expected Map, found ${map['resources'].getClass()}"
        }

        HelmStatusData.handleMissingKeysOrBadTypes(missingKeys, badTypes)

        this.status = map['status'] as String
        this.description = map['description'] as String
        this.lastDeployed = map['lastDeployed'] as String
        this.resources = map['resources'] as Map
    }

}

//Relevant helm status resource data we want to capture. This is inside the info data.
@TypeChecked
class HelmStatusResourceData {

    String apiVersion
    String kind
    String metadataName
    // for kind "Pod" containerStatusData may be present
    List<HelmStatusContainerStatusData> containerStatuses = []

    @SuppressWarnings(['Instanceof'])
    HelmStatusResourceData(Map<String, Object> map) {
        def missingKeys = []
        def badTypes = []

        def stringTypes = [ "apiVersion", "kind", "metadataName"]
        for (att in stringTypes) {
            if (!map.containsKey(att)) {
                missingKeys << att
            } else if (!(map[att] instanceof String)) {
                badTypes << "${att}: expected String, found ${map[att].getClass()}"
            }
        }

        HelmStatusData.handleMissingKeysOrBadTypes(missingKeys, badTypes)

        this.apiVersion = map['apiVersion'] as String
        this.kind = map['kind'] as String
        this.metadataName = map['metadataName'] as String
        if (map.containsKey('containerStatuses')) {
            this.containerStatuses = (map['containerStatuses'] as List<HelmStatusContainerStatusData>)
        }
    }

}

@TypeChecked
class HelmStatusContainerStatusData {

    String name
    String image
    String imageID

    @SuppressWarnings(['Instanceof'])
    HelmStatusContainerStatusData(Map<String, Object> map) {
        def missingKeys = []
        def badTypes = []

        def stringTypes = [ "name", "image", "imageID"]
        for (att in stringTypes) {
            if (!map.containsKey(att)) {
                missingKeys << att
            } else if (!(map[att] instanceof String)) {
                badTypes << "${att}: expected String, found ${map[att].getClass()}"
            }
        }
        HelmStatusData.handleMissingKeysOrBadTypes(missingKeys, badTypes)

        this.name = map['name'] as String
        this.image = map['image'] as String
        this.imageID = map['imageID'] as String
    }

}
