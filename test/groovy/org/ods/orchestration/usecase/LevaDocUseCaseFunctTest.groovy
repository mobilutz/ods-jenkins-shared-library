package org.ods.orchestration.usecase

import groovy.util.logging.Slf4j
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.ods.core.test.usecase.LevaDocUseCaseFactory
import org.ods.core.test.usecase.RepoDataBuilder
import org.ods.core.test.usecase.levadoc.fixture.DocTypeProjectFixture
import org.ods.core.test.usecase.levadoc.fixture.DocTypeProjectFixtureWithComponent
import org.ods.core.test.usecase.levadoc.fixture.DocTypeProjectFixtureWithTestData
import org.ods.core.test.usecase.levadoc.fixture.DocTypeProjectFixturesOverall
import org.ods.core.test.usecase.levadoc.fixture.LevaDocDataFixture
import org.ods.core.test.usecase.levadoc.fixture.ProjectFixture
import org.ods.services.BitbucketService
import org.ods.services.GitService
import org.ods.services.JenkinsService
import org.ods.services.OpenShiftService
import org.ods.util.UnirestConfig
import spock.lang.Specification
import spock.lang.Unroll
import util.FixtureHelper

import java.util.stream.Collectors

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
        LeVADocumentUseCase useCase = getLevaDocUseCase(projectFixture)

        when: "the user creates a LeVA document"
        useCase.createDocument(projectFixture.getDocType())

        then: "the generated PDF is as expected"
        true // TODO

        where: "Doctypes creation without params"
        projectFixture << getAllProjectFixtures()
    }

    private List<ProjectFixture> getAllProjectFixtures() {
        List<ProjectFixture> projects = new DocTypeProjectFixture().getProjects()
        projects.addAll(new DocTypeProjectFixtureWithTestData().getProjects())
        return projects
    }

    @Unroll
    def "create #projectFixture.docType for component #projectFixture.component and project: #projectFixture.project"() {
        given: "There's a LeVADocument service"
        LeVADocumentUseCase useCase = getLevaDocUseCase(projectFixture)
        Map input = RepoDataBuilder.getRepoForComponent(projectFixture.component)

        when: "the user creates a LeVA document"
        useCase.createDocument("${projectFixture.docType}", input, input.data)

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
        LeVADocumentUseCase useCase = getLevaDocUseCase(projectFixture)
        new LevaDocDataFixture(tempFolder.getRoot()).useExpectedComponentDocs(useCase, projectFixture)

        when: "the user creates a LeVA document"
        useCase.createDocument("OVERALL_${projectFixture.docType}")

        then: "the generated PDF is as expected"
        true // TODO

        where:
        projectFixture << new DocTypeProjectFixturesOverall().getProjects()
    }

    @Unroll
    def "upload #projectFixture.project xunit and jenkins log from workspace to nexus"() {
        given:
        LeVADocumentUseCase useCase = getLevaDocUseCase(projectFixture, "upload xunit and jenkins log")

        new LevaDocDataFixture(tempFolder.getRoot()).useExpectedComponentDocs(useCase, projectFixture)
        def projectKey = "${projectFixture.project}"
        String buildNumber = "${projectFixture.buildNumber}"

        String xunitFilesPathUnitBackend = "test/resources/workspace/${projectKey}/xunit-build-${buildNumber}/backend/unit/build/test-results/test"
        String xunitFilesPathUnitFrontend = "test/resources/workspace/${projectKey}/xunit-build-${buildNumber}/frontend/unit/build/test-results/test"
        String xunitFilesPathAcceptance = "test/resources/workspace/${projectKey}/xunit-build-${buildNumber}/test/acceptance/build/test-results"
        String xunitFilesPathInstallation = "test/resources/workspace/${projectKey}/xunit-build-${buildNumber}/test/installation/build/test-results"
        String xunitFilesPathIntegration = "test/resources/workspace/${projectKey}/xunit-build-${buildNumber}/test/integration/build/test-results"
        String projectId = projectFixture.project
        String workspacePath = tempFolder.getRoot().getAbsolutePath()
        String nexusDirectory = useCase.nexus.getNexusDirectory(projectId, buildNumber)

        when:
        log.info("Uploading tests results... ")
        def frontendUnitRes = useCase.nexus.uploadTestsResults("Unit", xunitFilesPathUnitFrontend, workspacePath, nexusDirectory, "frontend")
        def backendUnitRes = useCase.nexus.uploadTestsResults("Unit", xunitFilesPathUnitBackend, workspacePath, nexusDirectory,"backend")
        def acceptanceRes = useCase.nexus.uploadTestsResults("Acceptance", xunitFilesPathAcceptance, workspacePath, nexusDirectory)
        def integrationRes = useCase.nexus.uploadTestsResults("Integration", xunitFilesPathIntegration, workspacePath, nexusDirectory)
        def installationRes = useCase.nexus.uploadTestsResults("Installation", xunitFilesPathInstallation, workspacePath, nexusDirectory)

        log.info("Uploading jenkins log... ")
        String jenkinsLogPath = "test/resources/workspace/${projectKey}/jenkins-job-log.zip"
        def jenkinsLogJobRes = useCase.nexus.uploadJenkinsJobLog(projectKey, buildNumber, jenkinsLogPath)

        log.info("Checking...")
        then:
        acceptanceRes == "repository/leva-documentation/${projectFixture.project}/${buildNumber}/acceptance.zip"
        integrationRes == "repository/leva-documentation/${projectFixture.project}/${buildNumber}/integration.zip"
        installationRes == "repository/leva-documentation/${projectFixture.project}/${buildNumber}/installation.zip"
        frontendUnitRes == "repository/leva-documentation/${projectFixture.project}/${buildNumber}/unit-frontend.zip"
        backendUnitRes == "repository/leva-documentation/${projectFixture.project}/${buildNumber}/unit-backend.zip"
        jenkinsLogJobRes == "repository/leva-documentation/${projectFixture.project}/${buildNumber}/jenkins-job-log.zip"

        where:
        projectFixture = new DocTypeProjectFixture().getProjects().stream()
            .filter({ProjectFixture project -> project.project == "ordgp"})
            .collect(Collectors.toList())
            .get(0)
    }

    private LeVADocumentUseCase getLevaDocUseCase(ProjectFixture projectFixture, String subScenarioId = "") {
        levaDocWiremock = new LevaDocWiremock()
        levaDocWiremock.setUpWireMock(projectFixture, tempFolder.root, subScenarioId)

        // Mocks generation (spock don't let you add this outside a Spec)
        JenkinsService jenkins = Mock(JenkinsService)
        jenkins.unstashFilesIntoPath(_, _, _) >> true
        OpenShiftService openShiftService = Mock(OpenShiftService)
        GitService gitService = Mock(GitService)
        BitbucketService bitbucketService = Mock(BitbucketService)
        BitbucketTraceabilityUseCase bbT = Spy(new BitbucketTraceabilityUseCase(bitbucketService, null, null))
        bbT.generateSourceCodeReviewFile() >> new FixtureHelper()
            .getResource(BitbucketTraceabilityUseCaseSpec.EXPECTED_BITBUCKET_CSV).getAbsolutePath()

        LevaDocUseCaseFactory levaDocUseCaseFactory = new LevaDocUseCaseFactory(
            levaDocWiremock,
            gitService,
            tempFolder,
            jenkins,
            openShiftService,
            bbT,
            bitbucketService)

        return levaDocUseCaseFactory.build(projectFixture)

    }

}

