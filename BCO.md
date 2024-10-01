# Additional BCO configuration

The `bco` format supports additional "pass-through" options for certain BCO fields. These fields cannot be inferred automatically from a pipeline or run, and so must be entered through the config. External systems can use these config options to inject fields automatically.

The following config options are supported:

- `prov.formats.bco.provenance_domain.review`
- `prov.formats.bco.provenance_domain.derived_from`
- `prov.formats.bco.provenance_domain.obsolete_after`
- `prov.formats.bco.provenance_domain.embargo`
- `prov.formats.bco.usability_domain`
- `prov.formats.bco.description_domain.keywords`
- `prov.formats.bco.description_domain.xref`

These options correspond exactly to fields in the BCO JSON schema. Refer to the [BCO User Guide](https://docs.biocomputeobject.org/user_guide/) for more information about these fields.

Here is an example config based on the BCO User Guide:

```groovy
prov {
  formats {
    bco {
      provenance_domain {
        review = [
          [
            "status": "approved",
            "reviewer_comment": "Approved by GW staff. Waiting for approval from FDA Reviewer",
            "date": "2017-11-12T12:30:48-0400",
            "reviewer": [
              "name": "Charles Hadley King", 
              "affiliation": "George Washington University", 
              "email": "hadley_king@gwu.edu",
              "contribution": "curatedBy",
              "orcid": "https://orcid.org/0000-0003-1409-4549"
            ]
          ],
          [
            "status": "approved",
            "reviewer_comment": "The revised BCO looks fine",
            "date": "2017-12-12T12:30:48-0400",
            "reviewer": [
              "name": "Eric Donaldson", 
              "affiliation": "FDA", 
              "email": "Eric.Donaldson@fda.hhs.gov",
              "contribution": "curatedBy"
            ]
          ]
        ]
        derived_from = 'https://example.com/BCO_948701/1.0'
        obsolete_after = '2118-09-26T14:43:43-0400'
        embargo = [
          "start_time": "2000-09-26T14:43:43-0400",
          "end_time": "2000-09-26T14:43:45-0400"
        ]
      }
      usability_domain = [
        "Identify baseline single nucleotide polymorphisms (SNPs)[SO:0000694], (insertions)[SO:0000667], and (deletions)[SO:0000045] that correlate with reduced (ledipasvir)[pubchem.compound:67505836] antiviral drug efficacy in (Hepatitis C virus subtype 1)[taxonomy:31646]", 
        "Identify treatment emergent amino acid (substitutions)[SO:1000002] that correlate with antiviral drug treatment failure", 
        "Determine whether the treatment emergent amino acid (substitutions)[SO:1000002] identified correlate with treatment failure involving other drugs against the same virus", 
        "GitHub CWL example: https://github.com/mr-c/hive-cwl-examples/blob/master/workflow/hive-viral-mutation-detection.cwl#L20"
      ]
      description_domain {
        keywords = [
          "HCV1a", 
          "Ledipasvir", 
          "antiviral resistance", 
          "SNP", 
          "amino acid substitutions"
        ]
        xref = [
          [
            "namespace": "pubchem.compound",
            "name": "PubChem-compound",
            "ids": ["67505836"], 
            "access_time": "2018-13-02T10:15-05:00"
          ],
          [
            "namespace": "pubmed",
            "name": "PubMed",
            "ids": ["26508693"], 
            "access_time": "2018-13-02T10:15-05:00"
          ],
          [
            "namespace": "so",
            "name": "Sequence Ontology",
            "ids": ["SO:000002", "SO:0000694", "SO:0000667", "SO:0000045"],
            "access_time": "2018-13-02T10:15-05:00"
          ],
          [
            "namespace": "taxonomy",
            "name": "Taxonomy",
            "ids": ["31646"], 
            "access_time": "2018-13-02T10:15-05:00"
          ]
        ]
      }
    }
  }
}
```

Alternatively, you can use params to make it easier for an external system:

```groovy
prov {
  formats {
    bco {
      provenance_domain {
        review = params.bco_provenance_domain_review
        derived_from = params.bco_provenance_domain_derived_from
        obsolete_after = params.bco_provenance_domain_obsolete_after
        embargo = params.bco_provenance_domain_embargo
      }
      usability_domain = params.bco_usability_domain
      description_domain {
        keywords = params.bco_description_domain_keywords
        xref = params.bco_description_domain_xref
      }
    }
  }
}
```

This way, the pass-through options can be provided as JSON in a [params file](https://nextflow.io/docs/latest/reference/cli.html#run):

```jsonc
{
    "bco_provenance_domain_review": [
        // ...
    ],
    "derived_from": "...",
    "obsolete_after": "...",
    "embargo": {
        "start_time": "...",
        "end_time": "..."
    },
    "bco_usability_domain": [
        // ...
    ],
    "bco_description_domain_keywords": [
        // ...
    ],
    "bco_description_domain_xref": [
        // ...
    ]
}
```
