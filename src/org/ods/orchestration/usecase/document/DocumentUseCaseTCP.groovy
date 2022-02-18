package org.ods.orchestration.usecase.document


import org.ods.orchestration.usecase.LeVADocumentUseCase

class DocumentUseCaseTCP extends DocumentUseCase {

    @Override
    String getDocumentName() {
        return LeVADocumentUseCase.DocumentType.TCP.name()
    }
}
