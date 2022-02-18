package org.ods.orchestration.usecase.document


import org.ods.orchestration.usecase.LeVADocumentUseCase

class DocumentUseCaseTIP extends DocumentUseCase {

    @Override
    String getDocumentName() {
        return LeVADocumentUseCase.DocumentType.TIP.name()
    }
}
