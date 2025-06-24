export default {
    content: ['./index.html', './src/**/*.{js,jsx,ts,tsx}'],
    theme: {
        extend: {
            colors: {
                'terminal-green': '#00dd00',
                'terminal-gray': '#383838'
            },
            fontFamily: {
                mono: ['"VT323"', 'monospace']
            }
        }
    },
    plugins: []
};
