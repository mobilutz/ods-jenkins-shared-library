package org.ods.orchestration.usecase.document


import org.ods.orchestration.usecase.LeVADocumentUseCase

class DocumentUseCaseCSD extends DocumentUseCase {

    @Override
    String getDocumentName() {
        return LeVADocumentUseCase.DocumentType.CSD.name()
    }
}
