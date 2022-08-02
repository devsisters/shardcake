module.exports = {
    base: '/shardcake/',
    head: [
        ['link', {
            rel: 'icon',
            href: '/shardcake.png'
        }]
    ],
    locales: {
        '/': {
            lang: 'en-US',
            title: 'Shardcake',
            description: 'Entity Sharding library for Scala',
        }
    },
    themeConfig: {
        logo: '/shardcake.png',
        locales: {
            '/': {
                selectText: 'Language',
                label: 'English',
                nav: [
                    {
                        text: 'Documentation',
                        link: '/docs/'
                    },
                    {
                        text: 'About',
                        link: '/about/'
                    },
                    {
                        text: 'Github',
                        link: 'https://github.com/devsisters/shardcake'
                    }
                ],
                sidebar: {
                    '/docs/': [{
                        title: 'Documentation',
                        collapsable: false,
                        sidebarDepth: 2,
                        children: [
                            '',
                            'architecture',
                            'storage',
                            'messaging-protocol',
                            'serialization',
                            'health',
                            'examples'
                        ]
                    }]
                }
            }
        },
    }
}