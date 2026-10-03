#include <iostream>
#include "metadata_core.h"

int main(int argc, char **argv) {
    try {
        if(argc < 2) return 2;
        if(argc > 2 && std::string(argv[2]) == "clear") {
            PodciniTags::Fields fields;
            fields[3].append("1");
            PodciniTags::write(argv[1], fields);
            if(PodciniTags::read(argv[1]) != fields) return 3;
        } else if(argc > 2) {
            PodciniTags::Fields fields;
            fields[0].append(TagLib::String("Rock/Alternative", TagLib::String::UTF8));
            fields[0].append(TagLib::String("Jazz", TagLib::String::UTF8));
            fields[1].append(TagLib::String("Calm", TagLib::String::UTF8));
            fields[2].append(TagLib::String("Activity/Focus", TagLib::String::UTF8));
            fields[2].append(TagLib::String("Language/Fran\xc3\xa7" "ais", TagLib::String::UTF8));
            fields[2].append(TagLib::String("Literal/AC\\/DC", TagLib::String::UTF8));
            fields[2].append(TagLib::String("Unicode/\xf0\x9f\x8e\xb5", TagLib::String::UTF8));
            fields[2].append(TagLib::String("Punctuation/one, two; three", TagLib::String::UTF8));
            fields[2].append(TagLib::String("Literal/a\\\\b", TagLib::String::UTF8));
            fields[3].append("1");
            PodciniTags::write(argv[1], fields);
            if(PodciniTags::read(argv[1]) != fields) return 3;
        }
        const auto fields = PodciniTags::read(argv[1]);
        for(size_t i = 0; i < fields.size(); ++i)
            for(const auto &value : fields[i]) std::cout << PodciniTags::keys[i] << "=" << value.to8Bit(true) << '\n';
    } catch(const std::exception &e) { std::cerr << e.what() << '\n'; return 1; }
}
