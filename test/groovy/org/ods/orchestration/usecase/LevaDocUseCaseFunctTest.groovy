package org.ods.orchestration.usecase

import groovy.util.logging.Slf4j
import org.junit.Ignore
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.ods.core.test.usecase.LevaDocUseCaseFactory
import org.ods.core.test.usecase.levadoc.fixture.DocTypeProjectFixture
import org.ods.core.test.usecase.levadoc.fixture.DocTypeProjectFixtureWithComponent
import org.ods.core.test.usecase.levadoc.fixture.DocTypeProjectFixtureWithTestData
import org.ods.core.test.usecase.levadoc.fixture.DocTypeProjectFixturesOverall
import org.ods.core.test.usecase.levadoc.fixture.LevaDocDataFixture
import org.ods.core.test.usecase.levadoc.fixture.ProjectFixture
import org.ods.services.GitService
import org.ods.services.JenkinsService
import org.ods.services.OpenShiftService
import org.ods.util.UnirestConfig
import spock.lang.Specification
import spock.lang.Unroll
import util.FixtureHelper

import java.nio.file.Paths

/**
 * IMPORTANT:
 * - this test uses the Wiremock generated by a PACT contract to interact with DocGen
 * - the rest of the external dependencies are mocked by Wiremock server: record/play
 * - if we do a change that breaks the contract we should update it here:
 *      the https://github.com/opendevstack/ods-document-generation-svc/src/test/resources/pact
 *
 * ==>> HOW TO add more projects:
 *  In order to execute this against any project:
 *  1. Copy into test/resources/workspace/ID-project
 *      - metadata.yml: from Jenkins workspace
 *      - docs: from Jenkins workspace
 *      - xunit: from Jenkins workspace
 *      - ods-state: from Jenkins workspace
 *      - projectData: from Jenkins workspace (or release manager repo in BB)
 *  2. Add a 'release' component to metadata.yml (if not exist). Sample:
 *        - id: release
 *          name: /ID-project-release
 *          type: ods
 *  3. Update test/resources/leva-doc-functional-test-projects.yml
 *
 * ==>> HOW TO use record/play:
 *
 *  ie:
 *  - RECORD=false are the default value. So then it can be executed without external dependencies.
 *  - RECORD=true will record the external interactions with
 *
 */
@Slf4j
class LevaDocUseCaseFunctTest extends Specification {

    @Rule
    public TemporaryFolder tempFolder

    LevaDocWiremock levaDocWiremock

    def setup() {
        UnirestConfig.init()
    }

    def cleanup() {
        levaDocWiremock?.tearDownWiremock()
    }

    @Unroll
    def "create #projectFixture.docType for project: #projectFixture.project"() {
        given: "There's a LeVADocument service"
        LeVADocumentUseCase useCase = getLevaDocUseCaseFactory(projectFixture).loadProject(projectFixture).build()

        when: "the user creates a LeVA document"
        useCase."create${projectFixture.docType}"()

        then: "the generated PDF is as expected"
        true // TODO

        where: "Doctypes creation without params"
        projectFixture << new DocTypeProjectFixture().getProjects()
    }

    @Unroll
    def "create #projectFixture.docType with tests files for project: #projectFixture.project"() {
        given: "There's a LeVADocument service"
        LeVADocumentUseCase useCase = getLevaDocUseCaseFactory(projectFixture).loadProject(projectFixture).build()
        Map data = new LevaDocDataFixture(tempFolder.getRoot()).getAllResults(useCase)

        when: "the user creates a LeVA document"
        useCase."create${projectFixture.docType}"(null, data)

        then: "the generated PDF is as expected"
        true // TODO

        where: "Doctypes creation with data params"
        projectFixture << new DocTypeProjectFixtureWithTestData().getProjects()
    }

    @Unroll
    def "create #projectFixture.docType for component #projectFixture.component and project: #projectFixture.project"() {
        given: "There's a LeVADocument service"
        LeVADocumentUseCase useCase = getLevaDocUseCaseFactory(projectFixture).loadProject(projectFixture).build()
        Map input =  new LevaDocDataFixture(tempFolder.getRoot()).getInputParamsModule(projectFixture, useCase)

        when: "the user creates a LeVA document"
        useCase."create${projectFixture.docType}"(input, input.data)

        then: "the generated PDF is as expected"
        true // TODO

        where: "Doctypes creation with repo and data params"
        projectFixture << new DocTypeProjectFixtureWithComponent().getProjects()
    }

    /**
     * When creating a new test for a project, this test depends on
     * @return
     */
    @Unroll
    def "create Overall #projectFixture.docType for project: #projectFixture.project"() {
        given: "There's a LeVADocument service"
        LeVADocumentUseCase useCase = getLevaDocUseCaseFactory(projectFixture).loadProject(projectFixture).build()
        new LevaDocDataFixture(tempFolder.getRoot()).useExpectedComponentDocs(useCase, projectFixture)
        if (LeVADocumentUseCase.DocumentType.TIR.name().equals(projectFixture.docType)) {
            def nexusUrl = System.properties["nexus.url"]
            useCase.project.data.jenkinLog = "${nexusUrl}/repository/leva-documentation/${projectFixture.project}/666/jenkins-job-log.zip"
        }

        when: "the user creates a LeVA document"
        useCase."createOverall${projectFixture.docType}"()

        then: "the generated PDF is as expected"
        true // TODO

        where:
        projectFixture << new DocTypeProjectFixturesOverall().getProjects()
    }

    @Ignore
    @Unroll
    def "upload #projectFixture.project xunit and jenkins log from workspace to nexus"() {
        given:
        LeVADocumentUseCase useCase = getLevaDocUseCaseFactory(projectFixture).loadProject(projectFixture).build()
        new LevaDocDataFixture(tempFolder.getRoot()).useExpectedComponentDocs(useCase, projectFixture)
        def projectKey = "${projectFixture.project}".toUpperCase()
        def xunitFilesPathUnitBackend = Paths.get("test/resources/workspace/${projectKey}/xunit/backend/unit/build/test-results/test").toUri()
        def xunitFilesPathUnitFrontend = Paths.get("test/resources/workspace/${projectKey}/xunit/frontend/unit/build/test-results/test").toUri()
        def xunitFilesPathAcceptance = Paths.get("test/resources/workspace/${projectKey}/xunit/test/acceptance/build/test-results").toUri()
        def xunitFilesPathInstallation = Paths.get("test/resources/workspace/${projectKey}/xunit/test/installation/build/test-results").toUri()
        def xunitFilesPathIntegration = Paths.get("test/resources/workspace/${projectKey}/xunit/test/integration/build/test-results").toUri()
        String buildId = "666"
        String projectId = projectFixture.project
        String workspacePath = tempFolder.getRoot().getAbsolutePath()

        when:
        def frontendUnitRes = useCase.nexus.uploadTestsResults("Unit", projectId, xunitFilesPathUnitFrontend, workspacePath, buildId, "frontend")
        def backendUnitRes = useCase.nexus.uploadTestsResults("Unit", projectId, xunitFilesPathUnitBackend, workspacePath, buildId, "backend")
        def acceptanceRes = useCase.nexus.uploadTestsResults("Acceptance", projectId, xunitFilesPathAcceptance, workspacePath, buildId)
        def integrationRes = useCase.nexus.uploadTestsResults("Integration", projectId, xunitFilesPathIntegration, workspacePath, buildId)
        def installationRes = useCase.nexus.uploadTestsResults("Installation", projectId, xunitFilesPathInstallation, workspacePath, buildId)

        InputStream jenkinsJobLogInputStream = Paths.get("test/resources/workspace/${projectKey}/jenkins-job-log.zip").toFile().newDataInputStream()
        def jenkinsLogJobRes = useCase.uploadJenkinsJobLog(projectKey, buildId, jenkinsJobLogInputStream)

        then:
        acceptanceRes.endsWith("/repository/leva-documentation/${projectFixture.project}/${buildId}/acceptance.zip")
        integrationRes.endsWith("/repository/leva-documentation/${projectFixture.project}/${buildId}/integration.zip")
        installationRes.endsWith("/repository/leva-documentation/${projectFixture.project}/${buildId}/installation.zip")
        frontendUnitRes.endsWith("/repository/leva-documentation/${projectFixture.project}/${buildId}/unit-frontend.zip")
        backendUnitRes.endsWith("/repository/leva-documentation/${projectFixture.project}/${buildId}/unit-backend.zip")
        jenkinsLogJobRes.endsWith("/repository/leva-documentation/${projectFixture.project}/${buildId}/jenkins-job-log.zip")

        where:
        projectFixture << new DocTypeProjectFixture().getProjects()
    }

    private LevaDocUseCaseFactory getLevaDocUseCaseFactory(ProjectFixture projectFixture) {
        levaDocWiremock = new LevaDocWiremock()
        levaDocWiremock.setUpWireMock(projectFixture, tempFolder.root)

        // Mocks generation (spock don't let you add this outside a Spec)
        JenkinsService jenkins = Mock(JenkinsService)
        jenkins.unstashFilesIntoPath(_, _, _) >> true
        OpenShiftService openShiftService = Mock(OpenShiftService)
        GitService gitService = Mock(GitService)
        BitbucketTraceabilityUseCase bbT = Spy(new BitbucketTraceabilityUseCase(null, null, null))
        bbT.generateSourceCodeReviewFile() >> new FixtureHelper()
            .getResource(BitbucketTraceabilityUseCaseSpec.EXPECTED_BITBUCKET_CSV).getAbsolutePath()

        return new LevaDocUseCaseFactory(
            levaDocWiremock,
            gitService,
            tempFolder,
            jenkins,
            openShiftService,
            bbT)
    }

}

